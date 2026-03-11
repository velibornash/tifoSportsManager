package org.example.footballmanager.repository;

import org.example.footballmanager.model.User;
	import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
	    @Override
	    @EntityGraph(attributePaths = {"team", "team.country", "team.competition", "team.competition.country"})
	    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
}