package com.joaoalcantara.triagem.infra;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Devolve o ambiente ao estado inicial entre um teste e outro.
 *
 * <p>Os testes de integracao compartilham o mesmo contexto do Spring, e com ele
 * tudo o que guarda estado: o banco (chamados, cache, limites e orcamentos), as
 * respostas e contagens dos WireMocks e os circuitos dos provedores. Sem a limpeza, um teste que provoca tres falhas
 * deixaria o circuito do Gemini a duas falhas de abrir, e o teste seguinte
 * falharia por um motivo que nao e dele.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class AmbienteDeTeste {

    private final ChamadoRepositorio chamados;
    private final JdbcClient banco;
    private final WireMockServer gemini;
    private final WireMockServer groq;
    private final CircuitBreakerRegistry disjuntores;

    public AmbienteDeTeste(ChamadoRepositorio chamados, JdbcClient banco, WireMockServer gemini, WireMockServer groq,
                           CircuitBreakerRegistry disjuntores) {
        this.chamados = chamados;
        this.banco = banco;
        this.gemini = gemini;
        this.groq = groq;
        this.disjuntores = disjuntores;
    }

    public void limpar() {
        chamados.deleteAll();
        banco.sql("DELETE FROM classificacoes_em_cache").update();
        banco.sql("DELETE FROM uso_por_cliente").update();
        banco.sql("DELETE FROM uso_diario_dos_provedores").update();
        gemini.resetAll();
        groq.resetAll();
        reiniciarCircuitos();
    }

    /** Fecha os circuitos de todos os provedores, como se nenhuma falha tivesse acontecido. */
    public void reiniciarCircuitos() {
        disjuntores.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }
}
