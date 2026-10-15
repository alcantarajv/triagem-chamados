package com.joaoalcantara.triagem.triagem;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.joaoalcantara.triagem.chamado.Chamado;
import com.joaoalcantara.triagem.chamado.ChamadoRepositorio;
import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.chamado.StatusChamado;

/**
 * A fila de triagem, que e a propria tabela de chamados.
 *
 * <p>Cada metodo e uma transacao curta. A chamada ao provedor de IA acontece
 * <b>entre</b> elas, nunca dentro: segurar uma transacao aberta — e com ela uma
 * conexao do pool e o lock da linha — durante os segundos que o modelo leva
 * para responder faria a lentidao do provedor esgotar o pool do banco, e entao
 * ate o {@code POST} de chamados pararia.</p>
 *
 * <p>Sem a transacao em volta da chamada, o lock do {@code SKIP LOCKED} nao
 * protege o chamado durante a triagem. Quem protege e a reserva: um prazo
 * gravado no proprio chamado, dentro do qual nenhum outro worker o pega.</p>
 */
@Component
public class FilaDeTriagem {

    private static final Logger log = LoggerFactory.getLogger(FilaDeTriagem.class);

    private final ChamadoRepositorio repositorio;
    private final PropriedadesDoWorker propriedades;
    private final Clock relogio;

    public FilaDeTriagem(ChamadoRepositorio repositorio, PropriedadesDoWorker propriedades, Clock relogio) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    /** O que o worker precisa para triar, sem levar a entidade para fora da transacao. */
    public record ChamadoReservado(UUID id, String texto) {
    }

    /**
     * Reserva o proximo chamado a triar, se houver.
     *
     * <p>O {@code SKIP LOCKED} garante que dois workers nunca recebam o mesmo
     * chamado nesta busca; a reserva gravada garante que, depois do commit, os
     * outros continuem passando ao largo dele.</p>
     */
    @Transactional
    public Optional<ChamadoReservado> reservarProximo() {
        Instant agora = relogio.instant();
        return repositorio.buscarProximoParaTriagem(agora).map(chamado -> {
            chamado.reservarAte(agora.plus(propriedades.reserva()));
            return new ChamadoReservado(chamado.getId(), chamado.getTexto());
        });
    }

    @Transactional
    public void registrarTriagem(UUID id, Classificacao classificacao, String provedor) {
        Chamado chamado = repositorio.buscarParaAtualizar(id).orElseThrow();
        if (!aindaEsperaTriagem(chamado)) {
            return;
        }
        chamado.registrarTriagem(classificacao, provedor, relogio.instant());
    }

    @Transactional
    public void encaminharParaTriagemManual(UUID id) {
        Chamado chamado = repositorio.buscarParaAtualizar(id).orElseThrow();
        if (!aindaEsperaTriagem(chamado)) {
            return;
        }
        chamado.encaminharParaTriagemManual();
    }

    /**
     * Se a reserva venceu enquanto este worker esperava o provedor, outro worker
     * pode ter pegado o chamado e terminado antes. O lock da linha serializa as
     * duas gravacoes, e a segunda encontra o trabalho feito e desiste.
     */
    private boolean aindaEsperaTriagem(Chamado chamado) {
        if (chamado.getStatus() != StatusChamado.RECEBIDO) {
            log.info("Chamado {} ja esta {}; resultado descartado", chamado.getId(), chamado.getStatus());
            return false;
        }
        return true;
    }
}
