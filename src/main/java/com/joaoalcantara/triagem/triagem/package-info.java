/**
 * Triagem: worker que consome a fila no banco, validacao da saida do modelo e
 * orquestracao da cadeia de provedores.
 *
 * <p>Decide o que fazer com cada resposta: aceitar, pedir de novo com o erro de
 * validacao, passar para o proximo provedor ou mandar para triagem manual.</p>
 */
package com.joaoalcantara.triagem.triagem;
