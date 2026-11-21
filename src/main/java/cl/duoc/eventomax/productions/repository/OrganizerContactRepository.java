package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.OrganizerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OrganizerContactRepository extends JpaRepository<OrganizerContact, Long> {

    Optional<OrganizerContact> findByOrganizerId(String organizerId);

    Optional<OrganizerContact> findByOrganizerIdAndActiveTrue(String organizerId);

    boolean existsByOrganizerId(String organizerId);

    @Query("select c from OrganizerContact c where c.active = true and lower(c.userOid) = lower(:userOid)")
    java.util.List<OrganizerContact> findByActiveTrueAndUserOid(@Param("userOid") String userOid);

    java.util.List<OrganizerContact> findByActiveTrueAndEmailIgnoreCase(String email);

    @Modifying
    @Query(value = """
            INSERT INTO organizer_contacts
                (organizer_id, email, contact_name, phone, user_oid, active, created_at, updated_at, created_by, updated_by)
            VALUES
                (:organizerId, :email, :contactName, NULL, :userOid, TRUE, :now, :now, :actor, :actor)
            ON CONFLICT (organizer_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("organizerId") String organizerId,
            @Param("email") String email,
            @Param("contactName") String contactName,
            @Param("userOid") String userOid,
            @Param("now") LocalDateTime now,
            @Param("actor") String actor
    );
}

