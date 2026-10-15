package com.joaoalcantara.triagem.triagem;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do worker de triagem, lida de {@code triagem.worker.*}.
 *
 * @param habilitado      liga o agendamento; os testes desligam e acionam o worker a mao
 * @param intervalo       espera entre uma rodada e a seguinte
 * @param reserva         por quanto tempo um chamado reservado fica fora do alcance de outros
 *                        workers; precisa ser maior que o tempo maximo de uma triagem
 * @param maximoPorRodada quantos chamados uma rodada processa antes de devolver a vez
 */
@ConfigurationProperties("triagem.worker")
public record PropriedadesDoWorker(boolean habilitado, Duration intervalo, Duration reserva, int maximoPorRodada) {
}
