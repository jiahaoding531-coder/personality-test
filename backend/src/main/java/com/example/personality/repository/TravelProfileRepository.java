package com.example.personality.repository;

import com.example.personality.entity.TravelProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TravelProfileRepository extends JpaRepository<TravelProfile, Long> {

    Optional<TravelProfile> findBySessionId(Long sessionId);

    boolean existsBySessionId(Long sessionId);
}
