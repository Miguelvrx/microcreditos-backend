
package com.microcreditos.microcreditosbackend.service;

import com.microcreditos.microcreditosbackend.model.AccionAuditoria;
import com.microcreditos.microcreditosbackend.model.Cliente;
import com.microcreditos.microcreditosbackend.repository.ClienteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

@Service
public class ClienteService {

    private static final int TAMANO_MAXIMO_PAGINA = 50;


    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private AuditoriaService auditoriaService;

    public List<Cliente> listarTodos() {
        return clienteRepository.findAll();
    }

   
    @Transactional(readOnly = true)
    public Page<Cliente> buscarPagina(String texto, int pagina, int tamano) {
        String filtro = (texto == null) ? "" : texto.trim();
        int paginaSegura = Math.max(pagina, 0);
        int tamanoSeguro = Math.min(Math.max(tamano, 1), TAMANO_MAXIMO_PAGINA);

        // El id desempata para que el orden sea estable entre páginas.
        Pageable pageable = PageRequest.of(
                paginaSegura,
                tamanoSeguro,
                Sort.by("nombreCompleto").ascending().and(Sort.by("id").ascending()));

        return clienteRepository.buscar(filtro, pageable);
    }

    public Optional<Cliente> buscarPorId(Long id) {
        return clienteRepository.findById(id);
    }


    public Cliente guardar(Cliente cliente) {
        return clienteRepository.save(cliente);
    }

    @Transactional
    public Cliente crear(Cliente cliente) {
        Cliente guardado = clienteRepository.save(cliente);

        auditoriaService.registrar(
                AccionAuditoria.CLIENTE_CREADO,
                "Cliente",
                guardado.getId(),
                "Cliente creado: " + guardado.getNombreCompleto()
                        + ", documento: " + guardado.getNumeroDocumento());

        return guardado;
    }

    @Transactional
    public Cliente actualizar(Long id, Cliente datosActualizados) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado con id: " + id));

        cliente.setNombreCompleto(datosActualizados.getNombreCompleto());
        cliente.setNumeroDocumento(datosActualizados.getNumeroDocumento());
        cliente.setTelefono(datosActualizados.getTelefono());
        cliente.setDireccion(datosActualizados.getDireccion());

        Cliente guardado = clienteRepository.save(cliente);

        auditoriaService.registrar(
                AccionAuditoria.CLIENTE_EDITADO,
                "Cliente",
                guardado.getId(),
                "Cliente editado: " + guardado.getNombreCompleto()
                        + ", documento: " + guardado.getNumeroDocumento());

        return guardado;
    }

    @Transactional
    public void eliminar(Long id) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Cliente no encontrado con id: " + id));

        if (cliente.getCreditos() != null && !cliente.getCreditos().isEmpty()) {
            throw new IllegalStateException(
                    "No se puede eliminar un cliente con créditos asociados.");
        }

        // Se captura antes del borrado: después de deleteById() el objeto ya no existe.
        String nombre = cliente.getNombreCompleto();
        String documento = cliente.getNumeroDocumento();

        clienteRepository.deleteById(id);

        auditoriaService.registrar(
                AccionAuditoria.CLIENTE_ELIMINADO,
                "Cliente",
                id,
                "Cliente eliminado: " + nombre + ", documento: " + documento);
    }
}