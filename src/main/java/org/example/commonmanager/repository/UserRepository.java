package org.example.commonmanager.repository;

import org.example.commonmanager.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);

    default Optional<User> findByUsernameOrEmail(String value) {
        return findByUsername(value).or(() -> findByEmail(value));
    }
}