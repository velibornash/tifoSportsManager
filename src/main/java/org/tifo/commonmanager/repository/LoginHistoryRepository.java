package org.tifo.commonmanager.repository;

import org.tifo.commonmanager.model.LoginHistory;
import org.tifo.commonmanager.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {
    List<LoginHistory> findByUserOrderByLoginAtDesc(User user);
}
