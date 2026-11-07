package com.gdgoc.dashboard.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailDispatchLogRepository extends JpaRepository<EmailDispatchLog, Long> {

    Optional<EmailDispatchLog> findByProviderMessageId(String providerMessageId);

    Optional<EmailDispatchLog> findFirstByEventIdAndRecipientEmailOrderBySentAtDesc(Long eventId, String recipientEmail);

    Optional<EmailDispatchLog> findFirstByRecipientEmailOrderBySentAtDesc(String recipientEmail);

    List<EmailDispatchLog> findByEventIdOrderBySentAtDesc(Long eventId);

    List<EmailDispatchLog> findByEventIdAndDeliveryStatusOrderBySentAtDesc(Long eventId, EmailDeliveryStatus deliveryStatus);

    List<EmailDispatchLog> findByDeliveryStatusOrderBySentAtDesc(EmailDeliveryStatus deliveryStatus);

    List<EmailDispatchLog> findByRecipientEmailOrderBySentAtDesc(String recipientEmail);

    long countByEventIdAndDeliveryStatus(Long eventId, EmailDeliveryStatus deliveryStatus);
}
