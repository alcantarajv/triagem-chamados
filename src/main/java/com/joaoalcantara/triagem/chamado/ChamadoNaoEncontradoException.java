package com.joaoalcantara.triagem.chamado;

import java.util.UUID;

/** Nenhum chamado com o id pedido. Traduzida para 404. */
public class ChamadoNaoEncontradoException extends RuntimeException {

    public ChamadoNaoEncontradoException(UUID id) {
        super("Chamado %s nao encontrado".formatted(id));
    }
}
