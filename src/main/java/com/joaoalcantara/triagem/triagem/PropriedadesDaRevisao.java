package com.joaoalcantara.triagem.triagem;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Revisao de chamados pendentes, lida de {@code triagem.revisao.*}.
 *
 * @param intervalo       espera entre uma rodada do revisor e a seguinte
 * @param esperas         espera antes de cada nova tentativa automatica: a
 *                        primeira vale depois da primeira falha, a segunda
 *                        depois da segunda. Esgotada a lista, o chamado espera
 *                        so uma pessoa.
 * @param maximoPorRodada quantos pendentes uma rodada revisa antes de devolver a vez
 */
@ConfigurationProperties("triagem.revisao")
public record PropriedadesDaRevisao(Duration intervalo, List<Duration> esperas, int maximoPorRodada) {

    public PropriedadesDaRevisao {
        esperas = esperas == null ? List.of() : List.copyOf(esperas);
    }
}
