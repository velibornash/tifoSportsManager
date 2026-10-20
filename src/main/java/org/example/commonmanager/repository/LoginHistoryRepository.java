package org.example.commonmanager.repository;

import org.example.commonmanager.model.LoginHistory;
import org.example.commonmanager.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
    List<LoginHistory> findByUserOrderByLoginAtDesc(User user);
}
