package com.joaoalcantara.triagem.provedor;

import java.util.List;

/**
 * Um provedor de IA, do ponto de vista do resto do sistema: algo que responde
 * a um pedido de classificacao ou falha.
 *
 * <p>Os metodos devolvem o texto da resposta <b>como veio</b>, sem interpretar.
 * Decidir se aquilo e uma classificacao aceitavel e trabalho da triagem
 * ({@code ValidadorDeClassificacao}), que nao confia em provedor nenhum.</p>
 *
 * <p>Ninguem fora deste pacote ve HTTP, codigo de status ou excecao de SDK.
 * Toda falha de comunicacao sai como {@link FalhaNoProvedorException}.</p>
 */
public interface ProvedorDeIa {

    /** Nome curto e estavel, gravado no chamado e usado em logs e metricas. */
    String nome();

    /** Pede a classificacao do chamado. */
    String classificar(String texto);

    /**
     * Pede de novo, mostrando ao modelo a resposta anterior e por que ela foi
     * recusada.
     */
    String corrigir(String texto, String respostaAnterior, List<String> erros);
}
