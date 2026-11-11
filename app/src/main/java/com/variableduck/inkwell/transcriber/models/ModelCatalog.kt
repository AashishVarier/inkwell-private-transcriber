package com.variableduck.inkwell.transcriber.models

/** A downloadable model. Sizes and SHA-256 come from the Hugging Face file metadata. */
data class CatalogModel(
    val fileName: String,
    val title: String,
    val sizeBytes: Long,
    val sha256: String,
    val multilingual: Boolean,
    val guidance: String,
)

object ModelCatalog {
    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** The default model: the smallest and fastest. */
    const val DEFAULT_MODEL = "ggml-tiny.en.bin"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            fileName = "ggml-tiny.en.bin",
            title = "Tiny",
            sizeBytes = 77_704_715,
            sha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
            multilingual = false,
            guidance = "Fastest. Good for quick notes.",
        ),
        CatalogModel(
            fileName = "ggml-tiny.bin",
            title = "Tiny",
            sizeBytes = 77_691_713,
            sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            multilingual = true,
            guidance = "Fastest. Good for quick notes.",
        ),
        CatalogModel(
            fileName = "ggml-base.en.bin",
            title = "Base",
            sizeBytes = 147_964_211,
            sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
            multilingual = false,
            guidance = "A good balance of speed and accuracy.",
        ),
        CatalogModel(
            fileName = "ggml-base.bin",
            title = "Base",
            sizeBytes = 147_951_465,
            sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            multilingual = true,
            guidance = "A good balance of speed and accuracy.",
        ),
        CatalogModel(
            fileName = "ggml-small.en-q5_1.bin",
            title = "Small (q5_1)",
            sizeBytes = 190_098_681,
            sha256 = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
            multilingual = false,
            guidance = "Best accuracy, slower.",
        ),
        CatalogModel(
            fileName = "ggml-small-q5_1.bin",
            title = "Small (q5_1)",
            sizeBytes = 190_085_487,
            sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
            multilingual = true,
            guidance = "Best accuracy, slower.",
        ),
    )

    fun find(fileName: String): CatalogModel? = models.firstOrNull { it.fileName == fileName }
}
