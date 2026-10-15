package com.joaoalcantara.triagem.provedor;

import java.time.Duration;
import java.util.Optional;

/**
 * O provedor nao respondeu: erro HTTP, tempo esgotado, conexao recusada.
 *
 * <p>Carrega o que a politica de repeticao precisa para decidir sem conhecer o
 * SDK: se a falha e <b>transitoria</b> (tentar de novo pode dar certo) e, quando
 * o provedor disse, <b>quanto esperar</b> antes de tentar.</p>
 */
public class FalhaNoProvedorException extends RuntimeException {

    private final String provedor;
    private final boolean transitoria;
    private final Duration esperaPedida;

    private FalhaNoProvedorException(String provedor, String descricao, boolean transitoria,
                                     Duration esperaPedida, Throwable causa) {
        super("Falha no provedor %s: %s".formatted(provedor, descricao), causa);
        this.provedor = provedor;
        this.transitoria = transitoria;
        this.esperaPedida = esperaPedida;
    }

    /** Tempo esgotado, conexao perdida, 5xx, 429: tentar de novo pode dar certo. */
    public static FalhaNoProvedorException transitoria(String provedor, String descricao,
                                                       Duration esperaPedida, Throwable causa) {
        return new FalhaNoProvedorException(provedor, descricao, true, esperaPedida, causa);
    }

    /** Requisicao recusada pelo que ela e (400, 401, 404): repetir daria o mesmo resultado. */
    public static FalhaNoProvedorException definitiva(String provedor, String descricao, Throwable causa) {
        return new FalhaNoProvedorException(provedor, descricao, false, null, causa);
    }

    /**
     * O circuito do provedor esta aberto: a chamada nem saiu. Nao e transitoria
     * no sentido que importa aqui — tentar de novo daqui a um segundo daria o
     * mesmo circuito aberto —, entao a vez passa direto para o proximo provedor.
     */
    public static FalhaNoProvedorException circuitoAberto(String provedor, Throwable causa) {
        return new FalhaNoProvedorException(provedor, "circuito aberto, provedor pulado", false, null, causa);
    }

    public String provedor() {
        return provedor;
    }

    public boolean transitoria() {
        return transitoria;
    }

    /** O que o provedor pediu no cabecalho {@code Retry-After}, se pediu. */
    public Optional<Duration> esperaPedida() {
        return Optional.ofNullable(esperaPedida);
    }
}
