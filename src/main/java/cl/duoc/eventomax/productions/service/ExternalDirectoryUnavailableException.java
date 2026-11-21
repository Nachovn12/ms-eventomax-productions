package cl.duoc.eventomax.productions.service;

/**
 * Excepción lanzada cuando no es posible verificar la elegibilidad en Microsoft Entra ID / Graph
 * (desactivado, sin permisos, throttled 429 o error transitorio). Falla cerrado (HTTP 503).
 */
public class ExternalDirectoryUnavailableException extends RuntimeException {
    public ExternalDirectoryUnavailableException(String message) {
        super(message);
    }

    public ExternalDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
