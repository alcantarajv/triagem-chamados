package com.joaoalcantara.triagem.provedor;

import com.joaoalcantara.triagem.chamado.Classificacao;

/**
 * Um provedor de IA, do ponto de vista do resto do sistema: algo que classifica
 * um texto ou falha.
 *
 * <p>Ninguem fora deste pacote ve HTTP, codigo de status ou excecao de SDK.
 * Toda falha sai como {@link FalhaNoProvedorException}, e e isso que permite a
 * triagem decidir o que fazer sem conhecer o provedor.</p>
 */
public interface ProvedorDeIa {

    /** Nome curto e estavel, gravado no chamado e usado em logs e metricas. */
    String nome();

    Classificacao classificar(String texto);
}
