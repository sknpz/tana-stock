package io.github.sknpz.tana.shared.exception;

/** Um campo/parâmetro da requisição é inválido. Vira HTTP 400 com o erro indexado pelo nome do campo. */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
