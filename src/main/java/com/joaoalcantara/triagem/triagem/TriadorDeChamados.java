package com.joaoalcantara.triagem.triagem;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.provedor.FalhaNoProvedorException;
import com.joaoalcantara.triagem.provedor.ProvedorDeIa;
import com.joaoalcantara.triagem.triagem.FilaDeTriagem.ChamadoReservado;

/**
 * Tria um chamado por vez: reserva, pergunta ao provedor, grava o resultado.
 *
 * <p>Sem {@code @Transactional} de proposito — ver {@link FilaDeTriagem}.</p>
 */
@Component
public class TriadorDeChamados {

    private static final Logger log = LoggerFactory.getLogger(TriadorDeChamados.class);

    private final FilaDeTriagem fila;
    private final ProvedorDeIa provedor;

    public TriadorDeChamados(FilaDeTriagem fila, ProvedorDeIa provedor) {
        this.fila = fila;
        this.provedor = provedor;
    }

    /**
     * Processa o proximo chamado da fila.
     *
     * @return {@code false} quando a fila estava vazia
     */
    public boolean processarProximo() {
        Optional<ChamadoReservado> reservado = fila.reservarProximo();
        if (reservado.isEmpty()) {
            return false;
        }
        triar(reservado.get());
        return true;
    }

    /**
     * Qualquer falha manda o chamado para triagem manual, e nunca o deixa
     * {@code RECEBIDO}. Deixa-lo na fila parece mais generoso, mas um chamado
     * que sempre falha — texto que o modelo nao consegue classificar, chave
     * invalida — seria pego de novo a cada vencimento da reserva, para sempre,
     * gastando cota a cada volta.
     *
     * <p>Nesta etapa a falha e definitiva para a triagem automatica. A nova
     * tentativa com o erro de validacao (Etapa 4), a repeticao de erros
     * transitorios (Etapa 5), o segundo provedor (Etapa 6) e o job que revisita
     * os pendentes (Etapa 7) entram aqui, um por vez.</p>
     *
     * <p>O texto do chamado nunca vai para o log: pode conter dados pessoais.</p>
     */
    private void triar(ChamadoReservado chamado) {
        try {
            Classificacao classificacao = provedor.classificar(chamado.texto());
            fila.registrarTriagem(chamado.id(), classificacao, provedor.nome());
            log.info("Chamado {} triado por {}", chamado.id(), provedor.nome());
        } catch (FalhaNoProvedorException e) {
            log.warn("Chamado {} vai para triagem manual: {}", chamado.id(), e.getMessage());
            fila.encaminharParaTriagemManual(chamado.id());
        } catch (RuntimeException e) {
            // A resposta veio, mas nao coube no chamado (campo nulo, resumo maior
            // que a coluna). A validacao da Etapa 4 passa a barrar isso antes.
            log.warn("Chamado {} vai para triagem manual: resposta de {} recusada ({})",
                    chamado.id(), provedor.nome(), e.getClass().getSimpleName());
            fila.encaminharParaTriagemManual(chamado.id());
        }
    }
}
