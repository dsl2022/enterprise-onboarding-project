package com.eop.notify;

import java.util.List;

/** The caller's own notification feed (contract {@code NotificationFeed}). {@code nextCursor} null = last page. */
public record NotificationFeed(List<Notification> items, int unreadCount, String nextCursor) {}
