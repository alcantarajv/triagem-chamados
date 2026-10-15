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

import com.joaoalcantara.triagem.custo.IdentificadorDeCliente;
import com.joaoalcantara.triagem.custo.LimiteDeChamadosExcedidoException;
import com.joaoalcantara.triagem.custo.LimiteDeChamadosPorCliente;
import com.joaoalcantara.triagem.infra.MetricasDaTriagem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/chamados")
public class ChamadoController {

    private final ChamadoServico servico;
    private final LimiteDeChamadosPorCliente limite;
    private final IdentificadorDeCliente identificador;
    private final MetricasDaTriagem metricas;

    public ChamadoController(ChamadoServico servico, LimiteDeChamadosPorCliente limite,
                             IdentificadorDeCliente identificador, MetricasDaTriagem metricas) {
        this.servico = servico;
        this.limite = limite;
        this.identificador = identificador;
        this.metricas = metricas;
    }

    /**
     * Registra um chamado e responde {@code 202 Accepted}.
     *
     * <p>202, e nao 201: o recurso foi criado, mas o trabalho que o cliente quer
     * de verdade — a triagem — ainda nao aconteceu. O 202 diz exatamente isso:
     * "recebi, vou processar". O cabecalho {@code Location} aponta para onde
     * acompanhar o resultado.</p>
     *
     * <p>O limite por cliente e conferido aqui, e nao no servico: e o endereco IP
     * da requisicao HTTP que identifica o cliente. Corpo invalido e recusado pelo
     * {@code @Valid} antes de chegar a este metodo, e nao gasta o limite.</p>
     */
    @PostMapping
    public ResponseEntity<ChamadoResposta> registrar(@Valid @RequestBody NovoChamado novo,
                                                     HttpServletRequest requisicao) {
        try {
            limite.consumir(identificador.enderecoDe(requisicao));
        } catch (LimiteDeChamadosExcedidoException e) {
            metricas.chamadoRecusado("limite_por_cliente");
            throw e;
        }
        ChamadoResposta corpo = ChamadoResposta.de(servico.registrar(novo.texto()));
        metricas.chamadoRegistrado();
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
