data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# ALB security group: only CloudFront's origin-facing ranges may reach :80.
resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb-sg"
  description = "ALB ingress from CloudFront only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from CloudFront"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name_prefix}-alb-sg" }
}

# Task security group: only the ALB may reach the container port.
resource "aws_security_group" "task" {
  name        = "${var.name_prefix}-task-sg"
  description = "Fargate task ingress from ALB only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "App port from ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name_prefix}-task-sg" }
}

resource "aws_lb" "this" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids
}

resource "aws_lb_target_group" "app" {
  name        = "${var.name_prefix}-tg"
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Fargate awsvpc

  health_check {
    path                = "/healthz"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# CloudFront in front of the ALB → free HTTPS on the default *.cloudfront.net domain.
resource "aws_cloudfront_distribution" "app" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${var.name_prefix} app (BFF) front door"
  price_class     = "PriceClass_100"

  origin {
    domain_name = aws_lb.this.dns_name
    origin_id   = "app-alb"
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only" # CloudFront->ALB over HTTP inside AWS
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # SPA origin: the Angular build in S3, read only via OAC (module.webapp).
  origin {
    domain_name              = var.spa_bucket_regional_domain_name
    origin_id                = "webapp-s3"
    origin_access_control_id = var.spa_oac_id
  }

  # DEFAULT: legacy HTML + the BFF (/, /api/v1, /auth/*). Untouched by the SPA add.
  default_cache_behavior {
    target_origin_id         = "app-alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # Managed-CachingDisabled
    origin_request_policy_id = "216adef6-5c7f-47e4-b989-5492eafa07d3" # Managed-AllViewer (forwards headers/cookies/qs)
  }

  # /app and /app/* -> the Angular SPA in S3. CachingOptimized is safe because the
  # build emits content-hashed asset names; the SPA-router function maps deep
  # links to /app/index.html. The frontend-deploy workflow invalidates /app/* on
  # each release so index.html updates immediately.
  ordered_cache_behavior {
    path_pattern           = "/app"
    target_origin_id       = "webapp-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6" # Managed-CachingOptimized
    function_association {
      event_type   = "viewer-request"
      function_arn = var.spa_function_arn
    }
  }

  ordered_cache_behavior {
    path_pattern           = "/app/*"
    target_origin_id       = "webapp-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6" # Managed-CachingOptimized
    function_association {
      event_type   = "viewer-request"
      function_arn = var.spa_function_arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}

# Bucket policy: only THIS distribution may read the SPA bucket (OAC, by SourceArn).
# Lives here because it needs the distribution ARN (defined above) and the bucket
# (passed in from module.webapp) — keeping it here avoids a module dependency cycle.
data "aws_iam_policy_document" "spa_bucket" {
  statement {
    sid       = "AllowCloudFrontOAC"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${var.spa_bucket_arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.app.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "spa" {
  bucket = var.spa_bucket_id
  policy = data.aws_iam_policy_document.spa_bucket.json
}
