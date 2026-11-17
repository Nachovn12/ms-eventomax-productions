package cl.duoc.eventomax.productions.repository;

import cl.duoc.eventomax.productions.model.OrganizerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrganizerContactRepository extends JpaRepository<OrganizerContact, Long> {

    Optional<OrganizerContact> findByOrganizerId(String organizerId);

    Optional<OrganizerContact> findByOrganizerIdAndActiveTrue(String organizerId);

    boolean existsByOrganizerId(String organizerId);

    @org.springframework.data.jpa.repository.Query("select c from OrganizerContact c where c.active = true and lower(c.userOid) = lower(:userOid)")
    java.util.List<OrganizerContact> findByActiveTrueAndUserOid(@org.springframework.data.repository.query.Param("userOid") String userOid);

    java.util.List<OrganizerContact> findByActiveTrueAndEmailIgnoreCase(String email);
}

