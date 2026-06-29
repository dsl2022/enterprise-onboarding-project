// CloudFront Function (viewer-request) — SPA deep-link fallback for the Angular
// app served under /app. Asset requests (anything with a file extension) pass
// through to S3; client-side routes (no extension, e.g. /app/dashboard) are
// rewritten to /app/index.html so the SPA router can take over.
//
// Scope is strictly /app: the default behavior (/, /api/v1, /auth/*) goes to the
// BFF via the ALB and never reaches this function.
function handler(event) {
  var request = event.request;
  var uri = request.uri;

  if (uri === '/app' || uri === '/app/') {
    request.uri = '/app/index.html';
    return request;
  }

  var lastSegment = uri.substring(uri.lastIndexOf('/') + 1);
  if (uri.startsWith('/app/') && lastSegment.indexOf('.') === -1) {
    request.uri = '/app/index.html';
  }

  return request;
}
