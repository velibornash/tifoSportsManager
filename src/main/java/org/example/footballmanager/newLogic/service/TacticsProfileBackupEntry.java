package org.example.footballmanager.newLogic.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacticsProfileBackupEntry {
    private String teamName;
    private String formation;
    private String style;
    private String rulesJson;
    private String setPiecesJson;
    private Long version;
    private LocalDateTime updatedAt;
}
