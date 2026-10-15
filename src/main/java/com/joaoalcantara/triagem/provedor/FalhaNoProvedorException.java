package com.joaoalcantara.triagem.provedor;

import java.time.Duration;
import java.util.Optional;

/**
 * O provedor nao produziu resposta: erro HTTP, tempo esgotado, conexao recusada
 * — ou a propria aplicacao decidiu nao chamar.
 *
 * <p>Carrega o que as politicas de resiliencia precisam para decidir sem
 * conhecer o SDK: se vale tentar de novo, quanto esperar quando o provedor
 * disse, e se a falha e do provedor (conta para o circuit breaker) ou uma
 * recusa nossa (nao conta).</p>
 */
public class FalhaNoProvedorException extends RuntimeException {

    public enum Tipo {
        /** Tempo esgotado, conexao perdida, 5xx, 429: tentar de novo pode dar certo. */
        TRANSITORIA,
        /** Requisicao recusada pelo que ela e (400, 401, 404): repetir daria o mesmo resultado. */
        DEFINITIVA,
        /** O circuito do provedor esta aberto: a chamada nem saiu. */
        CIRCUITO_ABERTO,
        /** O orcamento diario do provedor acabou: a chamada nem saiu. */
        ORCAMENTO_ESGOTADO
    }

    private final String provedor;
    private final Tipo tipo;
    private final Duration esperaPedida;

    private FalhaNoProvedorException(String provedor, String descricao, Tipo tipo, Duration esperaPedida,
                                     Throwable causa) {
        super("Falha no provedor %s: %s".formatted(provedor, descricao), causa);
        this.provedor = provedor;
        this.tipo = tipo;
        this.esperaPedida = esperaPedida;
    }

    public static FalhaNoProvedorException transitoria(String provedor, String descricao,
                                                       Duration esperaPedida, Throwable causa) {
        return new FalhaNoProvedorException(provedor, descricao, Tipo.TRANSITORIA, esperaPedida, causa);
    }

    public static FalhaNoProvedorException definitiva(String provedor, String descricao, Throwable causa) {
        return new FalhaNoProvedorException(provedor, descricao, Tipo.DEFINITIVA, null, causa);
    }

    /**
     * Nao e transitoria no sentido que importa aqui — tentar de novo daqui a um
     * segundo daria o mesmo circuito aberto —, entao a vez passa direto para o
     * proximo provedor.
     */
    public static FalhaNoProvedorException circuitoAberto(String provedor, Throwable causa) {
        return new FalhaNoProvedorException(provedor, "circuito aberto, provedor pulado", Tipo.CIRCUITO_ABERTO,
                null, causa);
    }

    public static FalhaNoProvedorException orcamentoEsgotado(String provedor, int orcamento) {
        return new FalhaNoProvedorException(provedor,
                "orcamento diario de %d requisicoes esgotado, provedor pulado ate a virada do dia".formatted(orcamento),
                Tipo.ORCAMENTO_ESGOTADO, null, null);
    }

    public String provedor() {
        return provedor;
    }

    public Tipo tipo() {
        return tipo;
    }

    public boolean transitoria() {
        return tipo == Tipo.TRANSITORIA;
    }

    /**
     * Se a falha diz algo sobre a saude do provedor. Circuito aberto e orcamento
     * esgotado sao decisoes desta aplicacao de nao chamar: contados como falha,
     * manteriam o circuito aberto por um motivo que nao e do provedor.
     */
    public boolean falhaDoProvedor() {
        return tipo == Tipo.TRANSITORIA || tipo == Tipo.DEFINITIVA;
    }

    /** O que o provedor pediu no {@code Retry-After} ou no corpo do 429, se pediu. */
    public Optional<Duration> esperaPedida() {
        return Optional.ofNullable(esperaPedida);
    }
}
