package com.joaoalcantara.triagem.triagem;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aciona o triador periodicamente.
 *
 * <p>Separado do {@link TriadorDeChamados} para que os testes desliguem so o
 * agendamento ({@code triagem.worker.habilitado=false}) e acionem o triador a
 * mao, no momento exato, sem esperar o relogio nem competir com uma execucao
 * em segundo plano.</p>
 */
@Component
@ConditionalOnBooleanProperty(name = "triagem.worker.habilitado", matchIfMissing = true)
public class AgendadorDaTriagem {

    private final TriadorDeChamados triador;
    private final PropriedadesDoWorker propriedades;

    public AgendadorDaTriagem(TriadorDeChamados triador, PropriedadesDoWorker propriedades) {
        this.triador = triador;
        this.propriedades = propriedades;
    }

    /**
     * Processa ate {@code maximoPorRodada} chamados e devolve a vez.
     *
     * <p>{@code fixedDelay}, e nao {@code fixedRate}: a proxima rodada so comeca
     * depois que esta terminar, entao um provedor lento nunca empilha rodadas.</p>
     */
    @Scheduled(fixedDelayString = "${triagem.worker.intervalo}")
    public void executar() {
        for (int i = 0; i < propriedades.maximoPorRodada(); i++) {
            if (!triador.processarProximo()) {
                return;
            }
        }
    }
}
