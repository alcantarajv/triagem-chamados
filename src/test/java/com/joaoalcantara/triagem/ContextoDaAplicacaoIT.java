package com.joaoalcantara.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.joaoalcantara.triagem.infra.PostgresDeTeste;

/**
 * Prova que o scaffold sobe de pe: contexto carregado e banco conectado.
 *
 * <p>Parece um teste trivial, e e — mas e o unico que falha cedo quando a
 * infraestrutura para de subir. Sem ele, um erro de configuracao do banco so
 * apareceria na etapa do worker, misturado com a logica que deveria estar sendo
 * testada ali.</p>
 *
 * <p>Ele tambem guarda uma regra do projeto: o contexto sobe sem nenhuma chave de
 * provedor de IA. No dia em que uma dependencia passar a exigir chave para subir,
 * este teste quebra no CI, que nao tem chave nenhuma.</p>
 *
 * <p>Sufixo {@code IT}: roda no {@code mvnw verify} pelo failsafe, e exige
 * Docker. O {@code mvnw test} continua rapido, so com os unitarios.</p>
 */
@SpringBootTest
@Import(PostgresDeTeste.class)
class ContextoDaAplicacaoIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void conecta_no_postgres() throws Exception {
        try (Connection conexao = dataSource.getConnection()) {
            assertThat(conexao.isValid(2)).isTrue();
            assertThat(conexao.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }
}
