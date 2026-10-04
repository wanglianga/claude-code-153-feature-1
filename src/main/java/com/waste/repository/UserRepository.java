package com.waste.repository;

import com.waste.entity.User;
import com.waste.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    List<User> findByRole(Role role);
    List<User> findByTeamName(String teamName);
}
