package com.joaoalcantara.triagem.chamado;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/chamados")
public class ChamadoController {

    private final ChamadoServico servico;

    public ChamadoController(ChamadoServico servico) {
        this.servico = servico;
    }

    /**
     * Registra um chamado e responde {@code 202 Accepted}.
     *
     * <p>202, e nao 201: o recurso foi criado, mas o trabalho que o cliente quer
     * de verdade — a triagem — ainda nao aconteceu. O 202 diz exatamente isso:
     * "recebi, vou processar". O cabecalho {@code Location} aponta para onde
     * acompanhar o resultado.</p>
     */
    @PostMapping
    public ResponseEntity<ChamadoResposta> registrar(@Valid @RequestBody NovoChamado novo) {
        ChamadoResposta corpo = ChamadoResposta.de(servico.registrar(novo.texto()));
        return ResponseEntity
                .accepted()
                .location(URI.create("/api/chamados/" + corpo.id()))
                .body(corpo);
    }

    @GetMapping("/{id}")
    public ChamadoResposta buscar(@PathVariable UUID id) {
        return ChamadoResposta.de(servico.buscar(id));
    }
}
