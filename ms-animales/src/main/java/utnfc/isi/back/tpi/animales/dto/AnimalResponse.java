package utnfc.isi.back.tpi.animales.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import utnfc.isi.back.tpi.animales.model.Animal;
import utnfc.isi.back.tpi.animales.model.EstadoAnimal;
import utnfc.isi.back.tpi.animales.model.Especie;
import utnfc.isi.back.tpi.animales.model.Tamanio;

/** Representacion publica de un animal. Nunca se expone la entidad JPA. */
public record AnimalResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Luna") String nombre,
        @Schema(example = "PERRO") Especie especie,
        @Schema(example = "24") int edadMeses,
        @Schema(example = "MEDIANO") Tamanio tamanio,
        @Schema(example = "Ninguna", nullable = true) String necesidadesEspeciales,
        @Schema(example = "true") boolean aptoNinios,
        @Schema(example = "true") boolean aptoOtrosAnimales,
        @Schema(example = "false") boolean requiereEspacioExterior,
        @Schema(example = "DISPONIBLE") EstadoAnimal estado,
        @Schema(example = "Tratamiento veterinario", nullable = true) String motivoNoDisponible) {

    public static AnimalResponse desde(Animal a) {
        return new AnimalResponse(a.getId(), a.getNombre(), a.getEspecie(), a.getEdadMeses(), a.getTamanio(),
                a.getNecesidadesEspeciales(), a.isAptoNinios(), a.isAptoOtrosAnimales(),
                a.isRequiereEspacioExterior(), a.getEstado(), a.getMotivoNoDisponible());
    }
}
