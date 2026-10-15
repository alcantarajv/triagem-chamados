/**
 * Adaptadores dos provedores de IA (Gemini e Groq), com tempo limite, nova
 * tentativa e circuit breaker por provedor.
 *
 * <p>E a unica parte do sistema que fala com o mundo externo. O resto do codigo
 * enxerga um provedor como algo que classifica ou falha, nunca como HTTP.</p>
 */
package com.joaoalcantara.triagem.provedor;
