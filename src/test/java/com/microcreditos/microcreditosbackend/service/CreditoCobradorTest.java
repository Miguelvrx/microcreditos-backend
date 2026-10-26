package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Credito;
import com.microcreditos.microcreditosbackend.model.RolUsuario;
import com.microcreditos.microcreditosbackend.model.Usuario;
import com.microcreditos.microcreditosbackend.repository.ClienteRepository;
import com.microcreditos.microcreditosbackend.repository.CreditoRepository;
import com.microcreditos.microcreditosbackend.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Asignación del cobrador a un crédito (V4): quién puede ser cobrador y cuándo se audita. */
@ExtendWith(MockitoExtension.class)
class CreditoCobradorTest {

    private static final long CREDITO_ID = 7L;

    @Mock private CreditoRepository creditoRepository;
    @Mock private ClienteRepository clienteRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private AuditoriaService auditoriaService;
    @InjectMocks private CreditoService service;

    private Credito credito;

    @BeforeEach
    void preparar() {
        credito = new Credito();
        credito.setId(CREDITO_ID);

        lenient().when(creditoRepository.findById(CREDITO_ID)).thenReturn(Optional.of(credito));
        lenient().when(creditoRepository.save(any(Credito.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Usuario usuario(long id, String nombre, RolUsuario rol, boolean activo) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setNombreUsuario(nombre);
        u.setPassword("hash-bcrypt-que-no-debe-salir");
        u.setRol(rol);
        u.setActivo(activo);
        lenient().when(usuarioRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void asignaUnCobradorActivo_loAudita_ySoloExponeIdYNombre() {
        Usuario ana = usuario(3L, "ana", RolUsuario.COBRADOR, true);

        Credito resultado = service.asignarCobrador(CREDITO_ID, 3L);

        assertThat(resultado.getCobrador()).isSameAs(ana);
        // Lo que sale en el JSON del crédito: un resumen, nunca la entidad con el hash de la contraseña
        assertThat(resultado.getCobradorAsignado()).isEqualTo(new Credito.CobradorResumen(3L, "ana"));
        verify(auditoriaService).registrar(eq(AccionAuditoria.COBRADOR_ASIGNADO), eq("Credito"),
                eq(CREDITO_ID), contains("ana"));
    }

    @Test
    void creditoSinCobrador_noExponeNada() {
        assertThat(credito.getCobradorAsignado()).isNull();
    }

    @Test
    void rechazaUnUsuarioQueNoEsCobrador() {
        usuario(4L, "operador1", RolUsuario.OPERADOR, true);

        assertThatThrownBy(() -> service.asignarCobrador(CREDITO_ID, 4L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no tiene rol COBRADOR");

        assertThat(credito.getCobrador()).isNull();
        verifyNoInteractions(auditoriaService);
    }

    @Test
    void rechazaUnCobradorDesactivado() {
        usuario(5L, "luis", RolUsuario.COBRADOR, false);

        assertThatThrownBy(() -> service.asignarCobrador(CREDITO_ID, 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("desactivado");

        verifyNoInteractions(auditoriaService);
    }

    @Test
    void sinCobrador_quitaLaAsignacionYLoAudita() {
        credito.setCobrador(usuario(3L, "ana", RolUsuario.COBRADOR, true));

        Credito resultado = service.asignarCobrador(CREDITO_ID, null);

        assertThat(resultado.getCobrador()).isNull();
        assertThat(resultado.getCobradorAsignado()).isNull();
        verify(auditoriaService).registrar(eq(AccionAuditoria.COBRADOR_ASIGNADO), eq("Credito"),
                eq(CREDITO_ID), contains("sin cobrador"));
    }

    @Test
    void elMismoCobrador_noGuardaNiAudita() {
        credito.setCobrador(usuario(3L, "ana", RolUsuario.COBRADOR, true));

        service.asignarCobrador(CREDITO_ID, 3L);

        verify(creditoRepository, never()).save(any(Credito.class));
        verifyNoInteractions(auditoriaService);
    }

    @Test
    void creditoInexistente_lanzaNoSuchElement() {
        assertThatThrownBy(() -> service.asignarCobrador(999L, 3L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Crédito no encontrado");
    }

    @Test
    void usuarioInexistente_lanzaNoSuchElement() {
        lenient().when(usuarioRepository.findById(888L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.asignarCobrador(CREDITO_ID, 888L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Usuario no encontrado");
    }
}