package com.joaoalcantara.triagem;

import org.springframework.boot.SpringApplication;

import com.joaoalcantara.triagem.infra.PostgresDeTeste;

/**
 * Sobe a aplicacao localmente com um PostgreSQL descartavel.
 *
 * <p>Atalho de desenvolvimento: rodar esta classe pela IDE dispensa o
 * {@code docker compose up} e a configuracao manual de conexao. O banco morre
 * com o processo, entao cada execucao comeca limpa.</p>
 */
public class TestTriagemApplication {

    public static void main(String[] args) {
        SpringApplication.from(TriagemApplication::main)
                .with(PostgresDeTeste.class)
                .run(args);
    }
}
