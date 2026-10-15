package com.joaoalcantara.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.joaoalcantara.triagem.infra.PostgresDeTeste;
import com.joaoalcantara.triagem.provedor.ProvedoresDeIa;
import com.joaoalcantara.triagem.triagem.AgendadorDaTriagem;

/**
 * Prova que a aplicacao sobe de pe: contexto carregado e banco conectado.
 *
 * <p>Parece um teste trivial, e e — mas e o unico que falha cedo quando a
 * infraestrutura para de subir. Sem ele, um erro de configuracao do banco so
 * apareceria no meio de outro teste, misturado com a logica que deveria estar
 * sendo testada ali.</p>
 *
 * <p>Ele tambem guarda uma regra do projeto: a aplicacao sobe <b>sem chave de
 * provedor nenhuma</b>, como no CI. Por isso nao usa {@code @TesteDeIntegracao},
 * que configura provedores falsos, e ainda apaga as chaves que o {@code .env}
 * local possa ter fornecido. No dia em que alguma dependencia passar a exigir
 * chave para subir, este teste quebra.</p>
 *
 * <p>Sufixo {@code IT}: roda no {@code mvnw verify} pelo failsafe, e exige
 * Docker. O {@code mvnw test} continua rapido, so com os unitarios.</p>
 */
@SpringBootTest(properties = {"triagem.provedores.gemini.chave=", "triagem.provedores.groq.chave="})
@Import(PostgresDeTeste.class)
class ContextoDaAplicacaoIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext contexto;

    @Test
    void conecta_no_postgres() throws Exception {
        try (Connection conexao = dataSource.getConnection()) {
            assertThat(conexao.isValid(2)).isTrue();
            assertThat(conexao.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    void sobe_sem_chave_e_com_o_worker_agendado() {
        assertThat(contexto.getBeansOfType(AgendadorDaTriagem.class)).hasSize(1);
        // Sem chave, nenhum provedor entra na cadeia: os chamados vao direto para
        // triagem manual, em vez de gastar uma requisicao fadada ao 401.
        assertThat(contexto.getBean(ProvedoresDeIa.class).emOrdem()).isEmpty();
    }
}
