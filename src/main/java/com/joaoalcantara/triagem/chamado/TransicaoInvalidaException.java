package com.joaoalcantara.triagem.chamado;

import java.util.UUID;

/** Tentativa de levar o chamado a um estado que o atual nao permite. */
public class TransicaoInvalidaException extends IllegalStateException {

    public TransicaoInvalidaException(UUID id, StatusChamado atual, StatusChamado destino) {
        super("O chamado %s esta %s e nao pode ir para %s".formatted(id, atual, destino));
    }
}
