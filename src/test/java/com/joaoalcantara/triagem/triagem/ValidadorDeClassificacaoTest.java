package com.joaoalcantara.triagem.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.joaoalcantara.triagem.chamado.Categoria;
import com.joaoalcantara.triagem.chamado.Classificacao;
import com.joaoalcantara.triagem.chamado.Prioridade;

class ValidadorDeClassificacaoTest {

    private final ValidadorDeClassificacao validador = new ValidadorDeClassificacao();

    private Classificacao valida(String resposta) {
        ResultadoDaValidacao resultado = validador.validar(resposta);
        assertThat(resultado).isInstanceOf(ResultadoDaValidacao.Valida.class);
        return ((ResultadoDaValidacao.Valida) resultado).classificacao();
    }

    private List<String> erros(String resposta) {
        ResultadoDaValidacao resultado = validador.validar(resposta);
        assertThat(resultado).isInstanceOf(ResultadoDaValidacao.Invalida.class);
        return ((ResultadoDaValidacao.Invalida) resultado).erros();
    }

    @Test
    @DisplayName("aceita a resposta no formato pedido")
    void respostaValida() {
        Classificacao classificacao = valida("""
                {"categoria":"TECNICO","prioridade":"ALTA","resumo":"Erro ao gerar relatorio"}
                """);

        assertThat(classificacao).isEqualTo(new Classificacao(Categoria.TECNICO, Prioridade.ALTA, "Erro ao gerar relatorio"));
    }

    @Test
    @DisplayName("tolerante na forma: maiusculas, espacos e campos a mais nao custam uma nova chamada")
    void toleranteNaForma() {
        Classificacao classificacao = valida("""
                {"categoria":" tecnico ","prioridade":"Alta","resumo":"  Erro ao gerar relatorio  ","confianca":0.9}
                """);

        assertThat(classificacao).isEqualTo(new Classificacao(Categoria.TECNICO, Prioridade.ALTA, "Erro ao gerar relatorio"));
    }

    @Test
    @DisplayName("aceita o JSON dentro de um bloco de codigo Markdown")
    void blocoDeCodigo() {
        Classificacao classificacao = valida("""
                ```json
                {"categoria":"DUVIDA","prioridade":"BAIXA","resumo":"Como exportar relatorio"}
                ```
                """);

        assertThat(classificacao.categoria()).isEqualTo(Categoria.DUVIDA);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    @DisplayName("resposta vazia e recusada")
    void respostaVazia(String resposta) {
        assertThat(erros(resposta)).containsExactly("a resposta veio vazia");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Claro! Este chamado e sobre acesso.",
            "{\"categoria\":\"ACESSO\",\"prioridade\":",
            "{categoria: ACESSO}"})
    @DisplayName("texto solto ou JSON cortado no meio e recusado")
    void naoEJson(String resposta) {
        assertThat(erros(resposta)).containsExactly("a resposta nao e um JSON valido");
    }

    @Test
    @DisplayName("JSON que nao e objeto e recusado")
    void naoEObjeto() {
        assertThat(erros("[\"ACESSO\", \"ALTA\"]")).containsExactly("a resposta nao e um objeto JSON");
    }

    @Test
    @DisplayName("categoria inventada e recusada, com a lista dos valores aceitos")
    void categoriaInventada() {
        assertThat(erros("""
                {"categoria":"URGENTE","prioridade":"ALTA","resumo":"Sistema fora do ar"}
                """)).containsExactly(
                "categoria 'URGENTE' nao existe; use um destes valores: ACESSO, FINANCEIRO, TECNICO, DUVIDA, OUTROS");
    }

    @Test
    @DisplayName("prioridade inventada e recusada")
    void prioridadeInventada() {
        assertThat(erros("""
                {"categoria":"TECNICO","prioridade":"URGENTISSIMA","resumo":"Sistema fora do ar"}
                """)).containsExactly(
                "prioridade 'URGENTISSIMA' nao existe; use um destes valores: BAIXA, MEDIA, ALTA, CRITICA");
    }

    @Test
    @DisplayName("campos ausentes ou nulos sao recusados")
    void camposAusentes() {
        assertThat(erros("{\"categoria\":null}"))
                .containsExactly("falta o campo categoria", "falta o campo prioridade", "falta o campo resumo");
    }

    @Test
    @DisplayName("resumo maior que o limite e recusado, sem ecoar o conteudo")
    void resumoLongoDemais() {
        String resumo = "a".repeat(Classificacao.TAMANHO_MAXIMO_DO_RESUMO + 1);

        assertThat(erros("""
                {"categoria":"TECNICO","prioridade":"ALTA","resumo":"%s"}
                """.formatted(resumo))).containsExactly("o resumo tem 121 caracteres; o maximo e 120");
    }

    @Test
    @DisplayName("resumo no limite exato e aceito")
    void resumoNoLimite() {
        String resumo = "a".repeat(Classificacao.TAMANHO_MAXIMO_DO_RESUMO);

        assertThat(valida("""
                {"categoria":"TECNICO","prioridade":"ALTA","resumo":"%s"}
                """.formatted(resumo)).resumo()).hasSize(Classificacao.TAMANHO_MAXIMO_DO_RESUMO);
    }

    @Test
    @DisplayName("resumo vazio, com quebra de linha ou que nao e texto e recusado")
    void resumoMalFormado() {
        assertThat(erros("{\"categoria\":\"TECNICO\",\"prioridade\":\"ALTA\",\"resumo\":\"   \"}"))
                .containsExactly("o resumo esta vazio");
        assertThat(erros("{\"categoria\":\"TECNICO\",\"prioridade\":\"ALTA\",\"resumo\":\"linha 1\\nlinha 2\"}"))
                .containsExactly("o resumo deve ter uma unica linha");
        assertThat(erros("{\"categoria\":\"TECNICO\",\"prioridade\":\"ALTA\",\"resumo\":42}"))
                .containsExactly("o resumo deve ser um texto");
    }

    @Test
    @DisplayName("todos os erros vem juntos: o modelo corrige tudo numa chamada so")
    void todosOsErrosJuntos() {
        assertThat(erros("""
                {"categoria":"URGENTE","prioridade":"MAXIMA","resumo":""}
                """)).hasSize(3);
    }

    @Test
    @DisplayName("valor inventado longo e ecoado truncado")
    void ecoTruncado() {
        String inventado = "X".repeat(200);

        assertThat(erros("""
                {"categoria":"%s","prioridade":"ALTA","resumo":"ok"}
                """.formatted(inventado)).getFirst()).startsWith("categoria '" + "X".repeat(30) + "...'");
    }
}
