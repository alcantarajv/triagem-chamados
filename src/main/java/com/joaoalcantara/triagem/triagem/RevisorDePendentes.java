package com.joaoalcantara.triagem.triagem;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tenta de novo, periodicamente, os chamados pendentes cuja nova tentativa
 * automatica ja chegou.
 *
 * <p>Separado do {@link AgendadorDaTriagem}, com intervalo proprio e mais longo:
 * chamado novo e urgente, e o worker o procura a cada poucos segundos; um
 * pendente ja esperou minutos por definicao, e procura-lo com a mesma
 * frequencia so gastaria consultas ao banco. E a fila de novos nunca espera
 * atras de uma revisao.</p>
 *
 * <p>Liga e desliga junto com o worker ({@code triagem.worker.habilitado}): os
 * testes acionam os dois a mao.</p>
 */
@Component
@ConditionalOnBooleanProperty(name = "triagem.worker.habilitado", matchIfMissing = true)
public class RevisorDePendentes {

    private final TriadorDeChamados triador;
    private final PropriedadesDaRevisao propriedades;

    public RevisorDePendentes(TriadorDeChamados triador, PropriedadesDaRevisao propriedades) {
        this.triador = triador;
        this.propriedades = propriedades;
    }

    @Scheduled(fixedDelayString = "${triagem.revisao.intervalo}")
    public void executar() {
        for (int i = 0; i < propriedades.maximoPorRodada(); i++) {
            if (!triador.processarProximoPendente()) {
                return;
            }
        }
    }
}
