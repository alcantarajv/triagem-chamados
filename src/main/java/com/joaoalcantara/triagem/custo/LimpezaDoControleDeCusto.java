package com.joaoalcantara.triagem.custo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Apaga o que o controle de custo ja nao usa: classificacoes vencidas, janelas
 * de limite encerradas e contadores de dias passados.
 *
 * <p>Sem ela, as tres tabelas so cresceriam. E, no caso do limite por cliente,
 * guardar o hash do IP alem da janela em que ele serve seria guardar dado de
 * quem acessou sem motivo.</p>
 */
@Component
@ConditionalOnBooleanProperty(name = "triagem.worker.habilitado", matchIfMissing = true)
public class LimpezaDoControleDeCusto {

    private static final Logger log = LoggerFactory.getLogger(LimpezaDoControleDeCusto.class);

    private final JdbcClient banco;
    private final Clock relogio;

    public LimpezaDoControleDeCusto(JdbcClient banco, Clock relogio) {
        this.banco = banco;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${triagem.custo.intervalo-da-limpeza}")
    public void limpar() {
        Instant agora = relogio.instant();
        int cache = banco.sql("DELETE FROM classificacoes_em_cache WHERE expira_em <= :agora")
                .param("agora", CacheDeClassificacao.utc(agora))
                .update();
        int janelas = banco.sql("DELETE FROM uso_por_cliente WHERE janela < :janelaAtual")
                .param("janelaAtual", CacheDeClassificacao.utc(agora.truncatedTo(ChronoUnit.HOURS)))
                .update();
        // Dois dias de folga: os provedores contam o dia em fusos diferentes, e o
        // "ontem" de um ainda pode ser o "hoje" de outro.
        int dias = banco.sql("DELETE FROM uso_diario_dos_provedores WHERE dia < :limite")
                .param("limite", LocalDate.ofInstant(agora, ZoneOffset.UTC).minusDays(2))
                .update();
        if (cache + janelas + dias > 0) {
            log.info("Limpeza do controle de custo: {} classificacoes vencidas, {} janelas de limite, {} dias de uso",
                    cache, janelas, dias);
        }
    }
}
