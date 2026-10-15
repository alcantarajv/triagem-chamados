package com.joaoalcantara.triagem.provedor;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Politica de nova tentativa para falhas transitorias, lida de
 * {@code triagem.repeticao.*}.
 *
 * @param tentativas    total de tentativas por chamada, contando a primeira
 * @param esperaInicial espera antes da segunda tentativa; dobra a cada nova falha
 * @param esperaMaxima  teto de qualquer espera, inclusive a pedida pelo provedor
 *                      no {@code Retry-After}. Se o provedor pedir mais que isso,
 *                      nao se espera: a falha segue adiante na hora.
 */
@ConfigurationProperties("triagem.repeticao")
public record PropriedadesDaRepeticao(int tentativas, Duration esperaInicial, Duration esperaMaxima) {

    public PropriedadesDaRepeticao {
        if (tentativas < 1) {
            throw new IllegalArgumentException("triagem.repeticao.tentativas precisa ser pelo menos 1");
        }
    }
}
