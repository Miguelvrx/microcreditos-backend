package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {

    @Mock
    private ClienteRepository clienteRepository;

    @Mock
    private AuditoriaService auditoriaService;

    @InjectMocks
    private ClienteService clienteService;

    private Page<Cliente> paginaVacia() {
        return new PageImpl<>(List.of());
    }

    @Test
    void textoNuloSeConvierteEnCadenaVacia() {
        when(clienteRepository.buscar(anyString(), any(Pageable.class))).thenReturn(paginaVacia());

        clienteService.buscarPagina(null, 0, 10);

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(clienteRepository).buscar(texto.capture(), any(Pageable.class));
        assertEquals("", texto.getValue());
    }

    @Test
    void textoSeRecortaDeEspacios() {
        when(clienteRepository.buscar(anyString(), any(Pageable.class))).thenReturn(paginaVacia());

        clienteService.buscarPagina("  Juan  ", 0, 10);

        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(clienteRepository).buscar(texto.capture(), any(Pageable.class));
        assertEquals("Juan", texto.getValue());
    }

    @Test
    void paginaNegativaYTamanoInvalidoSeCorrigen() {
        when(clienteRepository.buscar(anyString(), any(Pageable.class))).thenReturn(paginaVacia());

        clienteService.buscarPagina("", -5, 0);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(clienteRepository).buscar(anyString(), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(1, pageable.getValue().getPageSize());
    }

    @Test
    void tamanoSeLimitaA50() {
        when(clienteRepository.buscar(anyString(), any(Pageable.class))).thenReturn(paginaVacia());

        clienteService.buscarPagina("", 2, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(clienteRepository).buscar(anyString(), pageable.capture());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(50, pageable.getValue().getPageSize());
    }
}