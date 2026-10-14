package pl.chargeflow.session.session.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartChargingSessionRequest(@NotNull UUID reservationId) {}
