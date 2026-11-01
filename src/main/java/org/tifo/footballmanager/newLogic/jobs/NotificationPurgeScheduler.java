package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.newLogic.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes notifications older than 24 hours, once an hour.
 *
 * <p>Notifications are timely by nature: a badge about a forum reply from three days ago is not
 * something a manager can act on, and leaving every notification in the table forever only makes the
 * unread count and the dropdown slower. The owner's rule (2026-11-01): a notification is gone after
 * a day.
 */
@Component
public class NotificationPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationPurgeScheduler.class);

    private final NotificationService notificationService;

    public NotificationPurgeScheduler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void purge() {
        try {
            int removed = notificationService.purgeOlderThan24Hours();
            if (removed > 0) {
                log.info("Notification purge: removed {} notification(s) older than 24 hours.", removed);
            }
        } catch (RuntimeException e) {
            log.error("Notification purge failed", e);
        }
    }
}