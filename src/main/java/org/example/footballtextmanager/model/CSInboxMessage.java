package org.example.footballtextmanager.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CSInboxMessage {
    private String type;  // welcome, match, error, info, transfer
    private String text;
    private String timestamp;
    @Builder.Default
    private boolean read = false;
}
