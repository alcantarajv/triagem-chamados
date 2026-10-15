package com.joaoalcantara.triagem.triagem;

import java.util.List;

import com.joaoalcantara.triagem.chamado.Classificacao;

/** O que o validador concluiu sobre uma resposta do modelo. */
public sealed interface ResultadoDaValidacao {

    record Valida(Classificacao classificacao) implements ResultadoDaValidacao {
    }

    /** Todos os motivos da recusa, nao so o primeiro: e o que o modelo recebe para corrigir. */
    record Invalida(List<String> erros) implements ResultadoDaValidacao {

        public Invalida {
            erros = List.copyOf(erros);
        }
    }
}
