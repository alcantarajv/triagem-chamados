package com.joaoalcantara.triagem.custo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.chamado.Prioridade;
import com.joaoalcantara.triagem.infra.AmbienteDeTeste;
import com.joaoalcantara.triagem.infra.RelogioAjustavel.ClockMovel;
import com.joaoalcantara.triagem.infra.TesteDeIntegracao;

/**
 * A limpeza apaga so o que venceu. O agendamento fica desligado nos testes; o
 * teste chama a limpeza direto, no momento que escolhe.
 */
@TesteDeIntegracao
class LimpezaDoControleDeCustoIT {

    private static final Classificacao CLASSIFICACAO = new Classificacao(Categoria.DUVIDA, Prioridade.BAIXA, "Duvida");

    @Autowired
    private AmbienteDeTeste ambiente;

    @Autowired
    private JdbcClient banco;

    @Autowired
    private CacheDeClassificacao cache;

    @Autowired
    private LimiteDeChamadosPorCliente limite;

    @Autowired
    private PropriedadesDoCusto custo;

    @Autowired
    private ClockMovel relogio;

    private LimpezaDoControleDeCusto limpeza;

    @BeforeEach
    void limpar() {
        ambiente.limpar();
        limpeza = new LimpezaDoControleDeCusto(banco, relogio);
    }

    private int linhas(String tabela) {
        return banco.sql("SELECT count(*) FROM " + tabela).query(Integer.class).single();
    }

    @Test
    @DisplayName("apaga classificacoes vencidas e mantem as validas")
    void cacheVencido() {
        cache.guardar("texto antigo", CLASSIFICACAO, "gemini");
        relogio.avancar(custo.validadeDoCache().minusDays(1));
        cache.guardar("texto recente", CLASSIFICACAO, "gemini");
        relogio.avancar(Duration.ofDays(2));

        limpeza.limpar();

        assertThat(linhas("classificacoes_em_cache")).isEqualTo(1);
        assertThat(cache.buscar("texto recente")).isPresent();
    }

    @Test
    @DisplayName("apaga as janelas de limite encerradas: o hash do IP nao fica alem da hora em que serve")
    void janelasEncerradas() {
        limite.consumir("203.0.113.7");
        Instant proximaHora = relogio.instant().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
        relogio.avancar(Duration.between(relogio.instant(), proximaHora));
        limite.consumir("198.51.100.23");

        limpeza.limpar();

        assertThat(linhas("uso_por_cliente")).isEqualTo(1);
    }

    @Test
    @DisplayName("apaga contadores de dias passados, com dois dias de folga entre fusos")
    void diasPassados() {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), ZoneOffset.UTC);
        for (LocalDate dia : new LocalDate[] {hoje.minusDays(3), hoje.minusDays(2), hoje}) {
            banco.sql("INSERT INTO uso_diario_dos_provedores (provedor, dia, requisicoes) VALUES ('gemini', :dia, 10)")
                    .param("dia", dia)
                    .update();
        }

        limpeza.limpar();

        assertThat(linhas("uso_diario_dos_provedores")).isEqualTo(2);
    }
}
