package com.joaoalcantara.triagem.custo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.infra.Sha256;

/**
 * Limita quantos chamados um mesmo cliente registra por hora.
 *
 * <p>Cada chamado registrado vira pelo menos uma requisicao a um provedor de
 * IA, e a cota diaria dos provedores e compartilhada por todos os visitantes da
 * demonstracao. Sem limite, um unico script esgotaria o dia de todo mundo em
 * minutos. O cache nao protege disso: basta variar o texto.</p>
 *
 * <p>Janelas fixas de uma hora, contadas no banco com um unico
 * {@code INSERT ... ON CONFLICT ... RETURNING}: atomico, sem corrida entre
 * requisicoes simultaneas nem entre instancias. A janela fixa permite ate o
 * dobro do limite na virada da hora (o fim de uma e o comeco da outra); uma
 * janela deslizante evitaria isso, ao custo de guardar o horario de cada
 * chamado. Para proteger cota, o dobro ocasional e aceitavel.</p>
 *
 * <p>O cliente e guardado como hash do IP, nunca o IP em si, e as janelas
 * encerradas sao apagadas pela {@link LimpezaDoControleDeCusto}.</p>
 */
@Component
public class LimiteDeChamadosPorCliente {

    private static final Duration JANELA = Duration.ofHours(1);

    private final JdbcClient banco;
    private final PropriedadesDoCusto propriedades;
    private final Clock relogio;

    public LimiteDeChamadosPorCliente(JdbcClient banco, PropriedadesDoCusto propriedades, Clock relogio) {
        this.banco = banco;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    /**
     * Conta mais um chamado para o cliente na janela atual.
     *
     * @throws LimiteDeChamadosExcedidoException se ele ja tinha usado o limite
     */
    public void consumir(String enderecoDoCliente) {
        Instant agora = relogio.instant();
        Instant janela = agora.truncatedTo(ChronoUnit.HOURS);
        int usados = banco.sql("""
                        INSERT INTO uso_por_cliente (cliente, janela, chamados)
                        VALUES (:cliente, :janela, 1)
                        ON CONFLICT (cliente, janela) DO UPDATE SET chamados = uso_por_cliente.chamados + 1
                        RETURNING chamados
                        """)
                .param("cliente", Sha256.hex(enderecoDoCliente))
                .param("janela", CacheDeClassificacao.utc(janela))
                .query(Integer.class)
                .single();
        if (usados > propriedades.chamadosPorHora()) {
            throw new LimiteDeChamadosExcedidoException(propriedades.chamadosPorHora(),
                    Duration.between(agora, janela.plus(JANELA)));
        }
    }
}
