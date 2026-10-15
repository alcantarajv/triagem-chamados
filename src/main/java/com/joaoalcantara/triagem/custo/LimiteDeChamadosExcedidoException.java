package com.joaoalcantara.triagem.custo;

import java.time.Duration;

/** O cliente ja registrou o maximo de chamados da janela atual. Traduzida para 429. */
public class LimiteDeChamadosExcedidoException extends RuntimeException {

    private final Duration esperar;

    public LimiteDeChamadosExcedidoException(int limite, Duration esperar) {
        super("Limite de %d chamados por hora atingido. Tente de novo em %d minutos."
                .formatted(limite, Math.max(1, (esperar.toSeconds() + 59) / 60)));
        this.esperar = esperar;
    }

    /** Quanto falta para a janela virar: vai no cabecalho {@code Retry-After}. */
    public Duration esperar() {
        return esperar;
    }
}
