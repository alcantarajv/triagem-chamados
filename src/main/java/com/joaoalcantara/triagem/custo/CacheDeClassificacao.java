package com.joaoalcantara.triagem.custo;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.chamado.Prioridade;
import com.joaoalcantara.triagem.infra.Sha256;
import com.joaoalcantara.triagem.provedor.InstrucoesDeTriagem;

/**
 * Classificacoes ja obtidas, por texto normalizado: o mesmo texto nao gera
 * outra chamada ao provedor.
 *
 * <p>No banco, e nao em memoria: com mais de uma instancia, cada uma teria o seu
 * cache e pagaria pelo mesmo texto uma vez; e um reinicio — que no plano
 * gratuito do Render acontece a cada hibernacao — jogaria tudo fora.</p>
 *
 * <p>So entra aqui o que passou na validacao. Falha nao e guardada: o proximo
 * chamado com o mesmo texto merece uma tentativa de verdade.</p>
 *
 * <p>Dois chamados identicos triados ao mesmo tempo podem, os dois, nao achar
 * nada e pagar duas chamadas. Evitar isso exigiria travar a chave durante a
 * chamada ao provedor, e segurar lock durante chamada externa e justamente o
 * que a fila evita. O caso e raro, e o custo dele e uma requisicao.</p>
 */
@Component
public class CacheDeClassificacao {

    private final JdbcClient banco;
    private final PropriedadesDoCusto propriedades;
    private final Clock relogio;

    public CacheDeClassificacao(JdbcClient banco, PropriedadesDoCusto propriedades, Clock relogio) {
        this.banco = banco;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    /** Uma classificacao guardada e o provedor que a produziu originalmente. */
    public record ClassificacaoGuardada(Classificacao classificacao, String provedor) {
    }

    public Optional<ClassificacaoGuardada> buscar(String texto) {
        return banco.sql("""
                        SELECT categoria, prioridade, resumo, provedor
                        FROM classificacoes_em_cache
                        WHERE chave = :chave AND expira_em > :agora
                        """)
                .param("chave", chave(texto))
                .param("agora", utc(relogio.instant()))
                .query((linha, numero) -> new ClassificacaoGuardada(
                        new Classificacao(
                                Categoria.valueOf(linha.getString("categoria")),
                                Prioridade.valueOf(linha.getString("prioridade")),
                                linha.getString("resumo")),
                        linha.getString("provedor")))
                .optional();
    }

    public void guardar(String texto, Classificacao classificacao, String provedor) {
        Instant agora = relogio.instant();
        banco.sql("""
                        INSERT INTO classificacoes_em_cache
                            (chave, categoria, prioridade, resumo, provedor, criado_em, expira_em)
                        VALUES (:chave, :categoria, :prioridade, :resumo, :provedor, :agora, :expiraEm)
                        ON CONFLICT (chave) DO UPDATE SET
                            categoria = EXCLUDED.categoria,
                            prioridade = EXCLUDED.prioridade,
                            resumo = EXCLUDED.resumo,
                            provedor = EXCLUDED.provedor,
                            criado_em = EXCLUDED.criado_em,
                            expira_em = EXCLUDED.expira_em
                        """)
                .param("chave", chave(texto))
                .param("categoria", classificacao.categoria().name())
                .param("prioridade", classificacao.prioridade().name())
                .param("resumo", classificacao.resumo())
                .param("provedor", provedor)
                .param("agora", utc(agora))
                .param("expiraEm", utc(agora.plus(propriedades.validadeDoCache())))
                .update();
    }

    /**
     * SHA-256 do texto normalizado, precedido da impressao digital das
     * instrucoes: mudou o prompt, as classificacoes antigas deixam de casar.
     */
    static String chave(String texto) {
        return Sha256.hex(InstrucoesDeTriagem.impressaoDigital() + ":" + NormalizadorDeTexto.normalizar(texto));
    }

    // O driver do PostgreSQL nao aceita Instant direto; OffsetDateTime vira TIMESTAMPTZ sem ambiguidade.
    static OffsetDateTime utc(Instant instante) {
        return instante.atOffset(ZoneOffset.UTC);
    }
}
