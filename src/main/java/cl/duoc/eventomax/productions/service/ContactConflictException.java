package cl.duoc.eventomax.productions.service;

/**
 * Excepción lanzada ante un intento de sobrescribir o redirigir un contacto existente
 * desde un flujo no autorizado (HTTP 409 Conflict).
 */
public class ContactConflictException extends RuntimeException {
    public ContactConflictException(String message) {
        super(message);
    }
}
