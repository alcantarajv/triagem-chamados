package com.joaoalcantara.triagem.custo;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Conta as requisicoes a cada provedor no dia e recusa as que passariam do
 * orcamento.
 *
 * <p>O plano gratuito de cada provedor tem uma cota diaria (500 requisicoes no
 * Gemini, 1.000 no Groq). Estourar a cota nao custa dinheiro aqui, mas custa a
 * triagem: o provedor passa a responder 429 ate o dia virar, e cada chamado
 * paga as tentativas ate o circuito abrir. O orcamento fica um pouco abaixo da
 * cota e e conferido <i>antes</i> da requisicao — ela nem sai.</p>
 *
 * <p>O dia e contado no fuso em que o provedor conta: a cota do Gemini vira a
 * meia-noite do Pacifico, nao a de Brasilia nem a de UTC. Contar em outro fuso
 * deixaria uma janela de horas em que o orcamento ja zerou e a cota do provedor
 * nao.</p>
 *
 * <p>A reserva e uma unica instrucao SQL que so incrementa se ainda houver
 * saldo: duas instancias disputando a ultima requisicao do dia nao passam as
 * duas.</p>
 */
@Component
public class OrcamentoDiario {

    private final JdbcClient banco;
    private final Clock relogio;

    public OrcamentoDiario(JdbcClient banco, Clock relogio) {
        this.banco = banco;
        this.relogio = relogio;
    }

    /**
     * Reserva uma requisicao do orcamento de hoje.
     *
     * @return {@code false} se o orcamento do dia ja acabou
     */
    public boolean reservar(String provedor, int orcamento, ZoneId fuso) {
        if (orcamento <= 0) {
            return false;
        }
        return !banco.sql("""
                        INSERT INTO uso_diario_dos_provedores (provedor, dia, requisicoes)
                        VALUES (:provedor, :dia, 1)
                        ON CONFLICT (provedor, dia) DO UPDATE
                            SET requisicoes = uso_diario_dos_provedores.requisicoes + 1
                            WHERE uso_diario_dos_provedores.requisicoes < :orcamento
                        RETURNING requisicoes
                        """)
                .param("provedor", provedor)
                .param("dia", hoje(fuso))
                .param("orcamento", orcamento)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    /** Requisicoes ja feitas hoje ao provedor. */
    public int usadoHoje(String provedor, ZoneId fuso) {
        return banco.sql("SELECT requisicoes FROM uso_diario_dos_provedores WHERE provedor = :provedor AND dia = :dia")
                .param("provedor", provedor)
                .param("dia", hoje(fuso))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private LocalDate hoje(ZoneId fuso) {
        return LocalDate.ofInstant(relogio.instant(), fuso);
    }
}
