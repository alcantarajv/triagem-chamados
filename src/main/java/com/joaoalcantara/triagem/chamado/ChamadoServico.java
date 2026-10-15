package com.joaoalcantara.triagem.chamado;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChamadoServico {

    private final ChamadoRepositorio repositorio;
    private final Clock relogio;

    public ChamadoServico(ChamadoRepositorio repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    /**
     * Grava o chamado como {@code RECEBIDO} e devolve. So isso.
     *
     * <p>Nenhuma chamada externa acontece aqui, e nunca vai acontecer: o tempo
     * desta transacao e o tempo de um INSERT. A triagem e feita depois, por um
     * worker que le os chamados recebidos — e a latencia do modelo de IA nunca
     * vira a latencia da API.</p>
     */
    @Transactional
    public Chamado registrar(String texto) {
        return repositorio.save(new Chamado(texto, relogio.instant()));
    }

    @Transactional(readOnly = true)
    public Chamado buscar(UUID id) {
        return repositorio.findById(id).orElseThrow(() -> new ChamadoNaoEncontradoException(id));
    }
}
