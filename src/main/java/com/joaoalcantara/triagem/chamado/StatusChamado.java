package com.joaoalcantara.triagem.chamado;

import java.util.Set;

/**
 * Situacao do chamado e as transicoes que ela permite.
 *
 * <pre>
 * RECEBIDO --&gt; TRIADO
 *     |           ^
 *     --&gt; PENDENTE_TRIAGEM_MANUAL
 * </pre>
 *
 * <p>{@code RECEBIDO} e o estado de quem acabou de chegar e espera o worker.
 * {@code PENDENTE_TRIAGEM_MANUAL} e para onde vai o chamado quando todos os
 * provedores falharam ou a resposta continuou invalida — e nao e um beco sem
 * saida: um job tenta de novo mais tarde, e uma pessoa tambem pode triar. Por
 * isso ele ainda chega a {@code TRIADO}. Nenhum caminho leva a "erro" ou
 * "perdido": o chamado sempre termina triado, mesmo que demore.</p>
 *
 * <p>Os conjuntos sao devolvidos por um {@code switch} em vez de preenchidos no
 * construtor porque uma constante de enum nao pode referenciar outra enquanto
 * as constantes ainda estao sendo criadas.</p>
 */
public enum StatusChamado {
    RECEBIDO,
    TRIADO,
    PENDENTE_TRIAGEM_MANUAL;

    /** Estados alcancaveis a partir deste. Vazio significa estado final. */
    public Set<StatusChamado> proximos() {
        return switch (this) {
            case RECEBIDO -> Set.of(TRIADO, PENDENTE_TRIAGEM_MANUAL);
            case PENDENTE_TRIAGEM_MANUAL -> Set.of(TRIADO);
            case TRIADO -> Set.of();
        };
    }

    public boolean permiteIrPara(StatusChamado destino) {
        return proximos().contains(destino);
    }

    public boolean ehFinal() {
        return proximos().isEmpty();
    }
}
