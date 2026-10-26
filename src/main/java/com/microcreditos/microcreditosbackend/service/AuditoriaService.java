package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Auditoria;
import com.microcreditos.microcreditosbackend.repository.AuditoriaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class AuditoriaService {

    private static final String USUARIO_SISTEMA = "SISTEMA";
    private static final int MAX_DETALLE = 500;

    private final AuditoriaRepository auditoriaRepository;

    public AuditoriaService(AuditoriaRepository auditoriaRepository) {
        this.auditoriaRepository = auditoriaRepository;
    }

    /**
     * Registra un evento de auditoría.
     *
     * Es @Transactional (REQUIRED): cuando se llama desde otro servicio
     * transaccional (aprobar, registrarPago, etc.) se une a SU transacción,
     * así que si la acción falla y se revierte, el registro de auditoría
     * también se revierte. Nunca queda auditada una acción que no ocurrió.
     */
    @Transactional
    public void registrar(AccionAuditoria accion, String entidad, Long entidadId, String detalle) {
        Auditoria a = new Auditoria();
        a.setUsuario(usuarioActual());
        a.setAccion(accion);
        a.setEntidad(entidad);
        a.setEntidadId(entidadId);
        a.setDetalle(recortar(detalle));
        a.setFecha(LocalDateTime.now());
        auditoriaRepository.save(a);
    }

    /**
     * Consulta paginada del historial, más reciente primero.
     * Todos los filtros son opcionales: solo se aplican los que no vienen nulos.
     *
     * @param usuario texto a buscar dentro del nombre de usuario (sin distinguir mayúsculas)
     * @param accion  acción exacta
     * @param desde   fecha inicial, inclusive (desde las 00:00)
     * @param hasta   fecha final, inclusive (hasta las 23:59:59)
     */
    @Transactional(readOnly = true)
    public Page<Auditoria> listar(String usuario, AccionAuditoria accion,
                                  LocalDate desde, LocalDate hasta, Pageable pageable) {

        Specification<Auditoria> spec = (root, query, cb) -> cb.conjunction();

        if (usuario != null && !usuario.isBlank()) {
            String patron = "%" + usuario.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) ->
                    cb.like(cb.lower(root.<String>get("usuario")), patron));
        }

        if (accion != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("accion"), accion));
        }

        if (desde != null) {
            LocalDateTime inicio = desde.atStartOfDay();
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.<LocalDateTime>get("fecha"), inicio));
        }

        if (hasta != null) {
            // "hasta" es inclusivo: se compara contra el inicio del día siguiente.
            LocalDateTime finExclusivo = hasta.plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) ->
                    cb.lessThan(root.<LocalDateTime>get("fecha"), finExclusivo));
        }

        // Orden fijo: más reciente primero; el id desempata registros del mismo instante.
        Pageable ordenado = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "fecha").and(Sort.by(Sort.Direction.DESC, "id")));

        return auditoriaRepository.findAll(spec, ordenado);
    }

    /**
     * Usuario autenticado en la petición actual (el que puso JwtAuthFilter).
     * Si no hay autenticación —caso de MoraSchedulerService, que corre en un
     * hilo propio sin request— se registra como "SISTEMA".
     */
    private String usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return USUARIO_SISTEMA;
        }
        return auth.getName();
    }

    private String recortar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= MAX_DETALLE ? texto : texto.substring(0, MAX_DETALLE);
    }
}