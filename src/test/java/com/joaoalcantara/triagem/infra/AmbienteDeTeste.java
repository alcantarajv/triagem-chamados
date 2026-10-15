package com.joaoalcantara.triagem.infra;

import org.springframework.boot.test.context.TestConfiguration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Devolve o ambiente ao estado inicial entre um teste e outro.
 *
 * <p>Os testes de integracao compartilham o mesmo contexto do Spring, e com ele
 * tudo o que guarda estado: o banco, as respostas e contagens dos WireMocks e os
 * circuitos dos provedores. Sem a limpeza, um teste que provoca tres falhas
 * deixaria o circuito do Gemini a duas falhas de abrir, e o teste seguinte
 * falharia por um motivo que nao e dele.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class AmbienteDeTeste {

    private final ChamadoRepositorio chamados;
    private final WireMockServer gemini;
    private final WireMockServer groq;
    private final CircuitBreakerRegistry disjuntores;

    public AmbienteDeTeste(ChamadoRepositorio chamados, WireMockServer gemini, WireMockServer groq,
                           CircuitBreakerRegistry disjuntores) {
        this.chamados = chamados;
        this.gemini = gemini;
        this.groq = groq;
        this.disjuntores = disjuntores;
    }

    public void limpar() {
        chamados.deleteAll();
        gemini.resetAll();
        groq.resetAll();
        disjuntores.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }
}
