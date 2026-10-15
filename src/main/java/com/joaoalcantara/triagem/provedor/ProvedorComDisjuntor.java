package com.joaoalcantara.triagem.provedor;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.joaoalcantara.triagem.infra.MetricasDaTriagem;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Envolve um provedor com um circuit breaker proprio.
 *
 * <p>Sem ele, um provedor fora do ar custa a cada chamado o tempo limite inteiro
 * (vezes as tentativas) antes de a vez passar para o proximo. Com ele, depois de
 * algumas falhas seguidas o provedor e pulado de imediato, sem requisicao
 * nenhuma, durante a janela de tempo aberto; passada a janela, <b>uma</b>
 * chamada de teste decide se ele volta ou se a janela recomeca.</p>
 *
 * <p>Fica <i>dentro</i> da nova tentativa ({@link ProvedorComRepeticao}): cada
 * tentativa conta como uma chamada para o circuito. Do lado de fora, uma
 * triagem com tres tentativas fracassadas contaria como uma falha so, e o
 * circuito levaria tres vezes mais requisicoes inuteis para abrir.</p>
 *
 * <p>Um circuito por provedor: o Gemini fora do ar nao pode tirar o Groq da
 * cadeia.</p>
 */
public class ProvedorComDisjuntor implements ProvedorDeIa {

    private static final Logger log = LoggerFactory.getLogger(ProvedorComDisjuntor.class);

    private final ProvedorDeIa provedor;
    private final CircuitBreaker disjuntor;
    private final MetricasDaTriagem metricas;

    public ProvedorComDisjuntor(ProvedorDeIa provedor, CircuitBreaker disjuntor, MetricasDaTriagem metricas) {
        this.provedor = provedor;
        this.disjuntor = disjuntor;
        this.metricas = metricas;
        this.disjuntor.getEventPublisher().onStateTransition(evento -> log.warn(
                "Circuito do provedor {}: {} -> {}", provedor.nome(),
                evento.getStateTransition().getFromState(), evento.getStateTransition().getToState()));
    }

    @Override
    public String nome() {
        return provedor.nome();
    }

    @Override
    public String classificar(String texto) {
        return protegido(() -> provedor.classificar(texto));
    }

    @Override
    public String corrigir(String texto, String respostaAnterior, List<String> erros) {
        return protegido(() -> provedor.corrigir(texto, respostaAnterior, erros));
    }

    private String protegido(Supplier<String> chamada) {
        try {
            return disjuntor.executeSupplier(chamada);
        } catch (CallNotPermittedException aberto) {
            metricas.provedorPulado(provedor.nome(), "circuito_aberto");
            throw FalhaNoProvedorException.circuitoAberto(provedor.nome(), aberto);
        }
    }
}
