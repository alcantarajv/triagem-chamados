package com.joaoalcantara.triagem.provedor;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit breaker de cada provedor, lido de {@code triagem.disjuntor.*}.
 *
 * @param falhasSeguidas quantas falhas seguidas abrem o circuito
 * @param tempoAberto    por quanto tempo o provedor fica pulado antes de ser testado de novo
 */
@ConfigurationProperties("triagem.disjuntor")
public record PropriedadesDoDisjuntor(int falhasSeguidas, Duration tempoAberto) {

    public PropriedadesDoDisjuntor {
        if (falhasSeguidas < 1) {
            throw new IllegalArgumentException("triagem.disjuntor.falhas-seguidas precisa ser pelo menos 1");
        }
    }
}
