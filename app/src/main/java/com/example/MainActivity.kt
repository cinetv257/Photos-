package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.LayerEntity
import com.example.data.model.ProjectEntity
import com.example.ui.LayerState
import com.example.ui.PhotoXViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ImageProcessor
import java.io.InputStream
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Dark slate skin theme by default
                PhotoXApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoXApp() {
    val context = LocalContext.current
    val viewModel: PhotoXViewModel = viewModel()

    val projects by viewModel.projects.collectAsState(initial = emptyList())
    val activeProject by viewModel.activeProject.collectAsState()
    val layers by viewModel.layersList.collectAsState()
    val activeLayerId by viewModel.activeLayerId.collectAsState()
    val selectedTool by viewModel.selectedTool.collectAsState()

    // Dialog state controllers
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showColorFillDialog by remember { mutableStateOf(false) }
    var showContourDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Rulers visiblity toggler
    var showRulers by remember { mutableStateOf(true) }

    // File selection launcher for pictures
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    // Start Camera RAW Development Pipeline
                    viewModel.triggerCameraRawImport(bitmap)
                    Toast.makeText(context, "Fichier RAW importé avec succès. Paramétrage Camera RAW ouvert.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erreur de décodage photo : ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Main structural holder
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_scaffold"),
        containerColor = ComposeColor(0xFF1E1E1E) // Slate charcoal Photoshop grey
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeProject == null) {
                // Splash empty state screen
                WorkspaceSelectorScreen(
                    projects = projects,
                    onCreateProjectClick = { showNewProjectDialog = true },
                    onSelectProject = { viewModel.selectProject(it.id) },
                    onDeleteProject = { viewModel.deleteProject(it.id) },
                    onQuickRawImport = {
                        // Generate a high quality demo landscape bitmap simulating RAW parameters
                        val size = 600
                        val rawBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(rawBmp)
                        val paint = Paint().apply { isAntiAlias = true }
                        // Draw simulated complex photo (Sunset over hills)
                        // Sky Sunset Gradient
                        val skyGrad = android.graphics.LinearGradient(0f, 0f, 0f, 320f, Color.parseColor("#FF5E3A"), Color.parseColor("#FF2A68"), android.graphics.Shader.TileMode.CLAMP)
                        paint.shader = skyGrad
                        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

                        // Glowing Sun (Overexposed highlight region)
                        paint.shader = null
                        paint.color = Color.parseColor("#FFFFE0")
                        canvas.drawCircle(size * 0.5f, 220f, 70f, paint)

                        // Blue Hills (Deep underexposed shadow region)
                        paint.color = Color.parseColor("#081C36")
                        val path = android.graphics.Path().apply {
                            moveTo(0f, size.toFloat())
                            lineTo(0f, 400f)
                            quadTo(size * 0.35f, 340f, size * 0.7f, 440f)
                            quadTo(size * 0.85f, 460f, size.toFloat(), 380f)
                            lineTo(size.toFloat(), size.toFloat())
                            close()
                        }
                        canvas.drawPath(path, paint)

                        viewModel.triggerCameraRawImport(rawBmp)
                        Toast.makeText(context, "Format RAW simulé chargé dans Camera RAW !", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                // ACTIVE PROJECT WORKSPACE
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. TOP MENU BAR
                    MenuBarComponent(
                        onNewClick = { showNewProjectDialog = true },
                        onOpenClick = { imagePickerLauncher.launch("image/*") },
                        onSaveClick = {
                            Toast.makeText(context, "Projet sauvegardé localement au format PHOTOX (Room DB + fichiers caches)", Toast.LENGTH_SHORT).show()
                        },
                        onExportClick = { format ->
                            val uri = viewModel.exportCompositedImage(context, format)
                            if (uri != null) {
                                Toast.makeText(context, "Image exportée ($format) : ${uri.path}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Erreur d'exportation", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onUndoClick = { viewModel.undo() },
                        onRedoClick = { viewModel.redo() },
                        onRemplirClick = { showColorFillDialog = true },
                        onContourClick = { showContourDialog = true },
                        onToggleRulers = { showRulers = !showRulers },
                        onFilterClick = { category, filter ->
                            viewModel.applyOfflineFilter(category, filter)
                            Toast.makeText(context, "Filtre [ $filter ] appliqué au calque sélectionné", Toast.LENGTH_SHORT).show()
                        },
                        onClearSelection = { viewModel.clearSelection() },
                        onQuitClick = { viewModel.selectProject(null) },
                        onAboutClick = { showAboutDialog = true }
                    )

                    HorizontalDivider(color = ComposeColor(0xFF2C2C2C), modifier = Modifier.height(1.dp))

                    // MAIN PANEL: VERTICAL TOOLS (Left) + WORK CANVAS (Center) + LAYERS (Right)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // 2. VERTICAL TOOLBAR (Left)
                        VerticalToolbarComponent(
                            selectedTool = selectedTool,
                            onToolSelected = { viewModel.selectTool(it) }
                        )

                        VerticalDivider(color = ComposeColor(0xFF2C2C2C), modifier = Modifier.width(1.dp).fillMaxHeight())

                        // 3. WORK CANVAS PANEL (Center)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(ComposeColor(0xFF161616))
                        ) {
                            ActiveCanvasComponent(
                                viewModel = viewModel,
                                showRulers = showRulers
                            )
                        }

                        VerticalDivider(color = ComposeColor(0xFF2C2C2C), modifier = Modifier.width(1.dp).fillMaxHeight())

                        // 4. PANNEAU DES CALQUES (Right)
                        LayersPanelComponent(
                            viewModel = viewModel,
                            layers = layers,
                            activeLayerId = activeLayerId
                        )
                    }

                    HorizontalDivider(color = ComposeColor(0xFF2C2C2C), modifier = Modifier.height(1.dp))

                    // 5. PROPERTIES PANEL (Bottom)
                    PropertiesPanelComponent(
                        selectedTool = selectedTool,
                        brushColorFlow = viewModel.brushColor,
                        brushSizeFlow = viewModel.brushSize,
                        isEraserSizeFlow = viewModel.isEraserSize,
                        magicWandToleranceFlow = viewModel.magicWandTolerance,
                        textInputFlow = viewModel.textInput,
                        shapeTypeFlow = viewModel.shapeType
                    )
                }
            }

            // --- CAMERA RAW OVERLAY SCREEN ---
            val rawBmp by viewModel.rawImportingBitmap.collectAsState()
            val rawPreview by viewModel.cameraRawPreview.collectAsState()

            if (rawBmp != null) {
                CameraRawWizardComponent(
                    originalBmp = rawBmp!!,
                    previewBmp = rawPreview,
                    viewModel = viewModel,
                    onApply = {
                        viewModel.commitCameraRawImport()
                        Toast.makeText(context, "Développement RAW appliqué au nouveau calque", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = {
                        viewModel.discardCameraRawImport()
                    }
                )
            }
        }
    }

    // --- DIALOGS CONTROLLERS ---
    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { template, customW, customH ->
                viewModel.createNewProject(template, customW, customH)
                showNewProjectDialog = false
            }
        )
    }

    if (showColorFillDialog) {
        ColorSelectDialog(
            title = "Remplir le calque",
            onColorSelected = { hex ->
                viewModel.applyRemplir(hex)
                showColorFillDialog = false
            },
            onDismiss = { showColorFillDialog = false }
        )
    }

    if (showContourDialog) {
        ColorSelectDialog(
            title = "Dessiner le contour",
            onColorSelected = { hex ->
                viewModel.applyContour(hex, 12f)
                showContourDialog = false
            },
            onDismiss = { showContourDialog = false }
        )
    }

    if (showAboutDialog) {
        Dialog(onDismissRequest = { showAboutDialog = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ComposeColor(0xFF252526),
                modifier = Modifier.padding(16.dp).testTag("about_dialog")
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = ComposeColor(0xFF3A8EED),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "PhotoX Offline Editor",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ComposeColor.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Photoshop-like fully local photo retouching studio. Custom canvas rendering, rich camera raw pipeline, layers compositions and vector styling 100% offline.",
                        color = ComposeColor.LightGray,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { showAboutDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF333333))
                    ) {
                        Text("Fermer", color = ComposeColor.White)
                    }
                }
            }
        }
    }
}

// --- SUB COMPONENTS ---

// 1. SPLASH / LAUNCHER SELECTOR
@Composable
fun WorkspaceSelectorScreen(
    projects: List<ProjectEntity>,
    onCreateProjectClick: () -> Unit,
    onSelectProject: (ProjectEntity) -> Unit,
    onDeleteProject: (ProjectEntity) -> Unit,
    onQuickRawImport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = null,
            tint = ComposeColor(0xFF3A8EED),
            modifier = Modifier.size(100.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Bienvenue dans PhotoX",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = ComposeColor.White
        )
        Text(
            text = "Le studio graphique 100% Hors-Ligne & performant",
            fontSize = 14.sp,
            color = ComposeColor.Gray
        )
        Spacer(modifier = Modifier.height(40.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onCreateProjectClick,
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF3A8EED)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(50.dp)
                    .testTag("btn_new_project")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ComposeColor.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Nouveau canevas", color = ComposeColor.White, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onQuickRawImport,
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF107C41)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(50.dp)
                    .testTag("btn_raw_import")
            ) {
                Icon(Icons.Default.Camera, contentDescription = null, tint = ComposeColor.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Charger fichier RAW (.DNG)", color = ComposeColor.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        if (projects.isNotEmpty()) {
            Text(
                text = "Projets Récents",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ComposeColor.White,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                projects.forEach { project ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ComposeColor(0xFF252526), RoundedCornerShape(8.dp))
                            .clickable { onSelectProject(project) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(ComposeColor(0xFF333333), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = ComposeColor.LightGray)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                color = ComposeColor.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Taille : ${project.width} x ${project.height} px",
                                color = ComposeColor.Gray,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = { onDeleteProject(project) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer projet", tint = ComposeColor(0xFFCF6679))
                        }
                    }
                }
            }
        }
    }
}

// 2. MENU BAR TOP DROPDOWN CONTROLLERS
@Composable
fun MenuBarComponent(
    onNewClick: () -> Unit,
    onOpenClick: () -> Unit,
    onSaveClick: () -> Unit,
    onExportClick: (String) -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onRemplirClick: () -> Unit,
    onContourClick: () -> Unit,
    onToggleRulers: () -> Unit,
    onFilterClick: (String, String) -> Unit,
    onClearSelection: () -> Unit,
    onQuitClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    var activeMenu by remember { mutableStateOf<String?>(null) }

    val menus = listOf(
        "Fichier", "Édition", "Image", "Calque", "Filtre", "Sélection", "Affichage"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ComposeColor(0xFF252526))
            .padding(vertical = 4.dp, horizontal = 12.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        menus.forEach { item ->
            Box {
                Text(
                    text = item,
                    fontSize = 13.sp,
                    color = ComposeColor.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { activeMenu = if (activeMenu == item) null else item }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                // Drop down options list
                DropdownMenu(
                    expanded = activeMenu == item,
                    onDismissRequest = { activeMenu = null },
                    modifier = Modifier.background(ComposeColor(0xFF2A2A2B))
                ) {
                    when (item) {
                        "Fichier" -> {
                            DropdownMenuItem(
                                text = { Text("Nouveau...", color = ComposeColor.White) },
                                onClick = { onNewClick(); activeMenu = null },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = ComposeColor.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Ouvrir Photo (RAW)...", color = ComposeColor.White) },
                                onClick = { onOpenClick(); activeMenu = null },
                                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null, tint = ComposeColor.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Enregistrer projet (.photox)", color = ComposeColor.White) },
                                onClick = { onSaveClick(); activeMenu = null },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = ComposeColor.LightGray) }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Exporter au format PNG", color = ComposeColor.White) },
                                onClick = { onExportClick("PNG"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter au format JPEG", color = ComposeColor.White) },
                                onClick = { onExportClick("JPEG"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter au format WebP", color = ComposeColor.White) },
                                onClick = { onExportClick("WEBP"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Quitter l'éditeur", color = ComposeColor.White) },
                                onClick = { onQuitClick(); activeMenu = null }
                            )
                        }
                        "Édition" -> {
                            DropdownMenuItem(
                                text = { Text("Annuler (Undo)", color = ComposeColor.White) },
                                onClick = { onUndoClick(); activeMenu = null },
                                leadingIcon = { Icon(Icons.Default.Undo, contentDescription = null, tint = ComposeColor.LightGray) }
                            )
                            DropdownMenuItem(
                                text = { Text("Rétablir (Redo)", color = ComposeColor.White) },
                                onClick = { onRedoClick(); activeMenu = null },
                                leadingIcon = { Icon(Icons.Default.Redo, contentDescription = null, tint = ComposeColor.LightGray) }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Remplir...", color = ComposeColor.White) },
                                onClick = { onRemplirClick(); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Contour...", color = ComposeColor.White) },
                                onClick = { onContourClick(); activeMenu = null }
                            )
                        }
                        "Image" -> {
                            DropdownMenuItem(
                                text = { Text("Ajustement de l'Échelle...", color = ComposeColor.White) },
                                onClick = { activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Rotation 90° Horaire", color = ComposeColor.White) },
                                onClick = { activeMenu = null }
                            )
                        }
                        "Calque" -> {
                            DropdownMenuItem(
                                text = { Text("Ajouter Nouveau Calque", color = ComposeColor.White) },
                                onClick = { activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Aplatir l'image", color = ComposeColor.White) },
                                onClick = { activeMenu = null }
                            )
                        }
                        "Filtre" -> {
                            // Subsections (Flous, Rendu, Netteté, Déformation, Bruit, Autres)
                            DropdownMenuItem(
                                text = { Text("Flou gaussien (FLU)", color = ComposeColor.White) },
                                onClick = { onFilterClick("FLU", "Flou gaussien"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Flou de mouvement", color = ComposeColor.White) },
                                onClick = { onFilterClick("FLU", "Flou de mouvement"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Flou radial", color = ComposeColor.White) },
                                onClick = { onFilterClick("FLU", "Flou radial"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Flou d'adoucissement", color = ComposeColor.White) },
                                onClick = { onFilterClick("FLU", "Flou d'adoucissement"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Nuages (RENDU)", color = ComposeColor.White) },
                                onClick = { onFilterClick("RENDU", "Nuages"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Éclairage central", color = ComposeColor.White) },
                                onClick = { onFilterClick("RENDU", "Éclairage"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Masque flou (NETTETÉ)", color = ComposeColor.White) },
                                onClick = { onFilterClick("NETTETÉ", "Masque flou"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Accentuation directe", color = ComposeColor.White) },
                                onClick = { onFilterClick("NETTETÉ", "Accentuation"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Ondulation (DÉFORMATION)", color = ComposeColor.White) },
                                onClick = { onFilterClick("DÉFORMATION", "Ondulation"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Sphérisation", color = ComposeColor.White) },
                                onClick = { onFilterClick("DÉFORMATION", "Sphérisation"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Ajouter du bruit", color = ComposeColor.White) },
                                onClick = { onFilterClick("BRUIT", "Ajouter du bruit"); activeMenu = null }
                            )
                            HorizontalDivider(color = ComposeColor(0xFF3A3A3B))
                            DropdownMenuItem(
                                text = { Text("Niveaux de gris", color = ComposeColor.White) },
                                onClick = { onFilterClick("AUTRES", "Niveaux de gris"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Sépia", color = ComposeColor.White) },
                                onClick = { onFilterClick("AUTRES", "Sépia"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Négatif", color = ComposeColor.White) },
                                onClick = { onFilterClick("AUTRES", "Négatif"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Posterisation", color = ComposeColor.White) },
                                onClick = { onFilterClick("AUTRES", "Posterisation"); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("Détection des contours Sobel", color = ComposeColor.White) },
                                onClick = { onFilterClick("AUTRES", "Détection des contours"); activeMenu = null }
                            )
                        }
                        "Sélection" -> {
                            DropdownMenuItem(
                                text = { Text("Annuler la sélection", color = ComposeColor.White) },
                                onClick = { onClearSelection(); activeMenu = null }
                            )
                        }
                        "Affichage" -> {
                            DropdownMenuItem(
                                text = { Text("Afficher/Masquer les Règles", color = ComposeColor.White) },
                                onClick = { onToggleRulers(); activeMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("À propos de PhotoX...", color = ComposeColor.White) },
                                onClick = { onAboutClick(); activeMenu = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 3. VERTICAL LEFT TOOLBAR
@Composable
fun VerticalToolbarComponent(
    selectedTool: String,
    onToolSelected: (String) -> Unit
) {
    val items = listOf(
        Pair("Déplacer", Icons.Default.OpenWith),
        Pair("Sélection", Icons.Default.CropFree),
        Pair("Lasso", Icons.Default.Gesture),
        Pair("Baguette magique", Icons.Default.AutoFixHigh),
        Pair("Recadrage", Icons.Default.Crop),
        Pair("Pipette", Icons.Default.Colorize),
        Pair("Tampon", Icons.Default.CopyAll),
        Pair("Pinceau", Icons.Default.Brush),
        Pair("Gomme", Icons.Default.FormatPaint), // Eraser replacement
        Pair("Dégradé", Icons.Default.Gradient),
        Pair("Texte", Icons.Default.TextFields),
        Pair("Formes", Icons.Default.Category)
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(60.dp)
            .background(ComposeColor(0xFF252526))
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        items.forEach { tool ->
            val isActive = selectedTool == tool.first
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) ComposeColor(0xFF0F7CDB) else ComposeColor.Transparent)
                    .clickable { onToolSelected(tool.first) }
                    .testTag("tool_${tool.first.lowercase().replace(" ", "_")}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.second,
                    contentDescription = tool.first,
                    tint = if (isActive) ComposeColor.White else ComposeColor.LightGray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// 4. ZONE DE TRAVAIL CENTRALE (Canevas avec Zoom/Pinch manipulation et rules)
@Composable
fun ActiveCanvasComponent(
    viewModel: PhotoXViewModel,
    showRulers: Boolean
) {
    val activeProject by viewModel.activeProject.collectAsState()
    val layers by viewModel.layersList.collectAsState()
    val selectionMask by viewModel.selectionMask.collectAsState()
    val selectedTool by viewModel.selectedTool.collectAsState()

    val proj = activeProject ?: return

    // Scale State
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Rulers dynamic dimension updates
    var canvasBoxWidth by remember { mutableStateOf(1) }
    var canvasBoxHeight by remember { mutableStateOf(1) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 4f)
        offset += offsetChange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Background click clears menu overlay or selection optionally
            }
            .onSizeChanged {
                canvasBoxWidth = it.width
                canvasBoxHeight = it.height
            }
    ) {
        // Optionnel: Dessiner des règles
        if (showRulers) {
            // Horiz ruler (Règle horizontale en haut)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .align(Alignment.TopStart)
                    .background(ComposeColor(0xFF1E1E1E))
            ) {
                val step = 20 * scale
                var cur = 0f
                var label = 0
                while (cur < size.width) {
                    val lineLen = if (label % 100 == 0) 12.dp.toPx() else if (label % 50 == 0) 8.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color = ComposeColor.DarkGray,
                        start = Offset(cur, 0f),
                        end = Offset(cur, lineLen),
                        strokeWidth = 1f
                    )
                    if (label % 100 == 0 && step > 5) {
                        // Text label
                    }
                    cur += step
                    label += 20
                }
            }

            // Vert ruler (Règle verticale à gauche)
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(20.dp)
                    .align(Alignment.TopStart)
                    .background(ComposeColor(0xFF1E1E1E))
            ) {
                val step = 20 * scale
                var cur = 0f
                var label = 0
                while (cur < size.height) {
                    val lineLen = if (label % 100 == 0) 12.dp.toPx() else if (label % 50 == 0) 8.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color = ComposeColor.DarkGray,
                        start = Offset(0f, cur),
                        end = Offset(lineLen, cur),
                        strokeWidth = 1f
                    )
                    cur += step
                    label += 20
                }
            }
        }

        // SCROLLABLE INTERACTIVE CANVASS AREA
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (showRulers) 20.dp else 0.dp, top = if (showRulers) 20.dp else 0.dp)
                .transformable(state = state)
                .pointerInput(selectedTool) {
                    // Interaction coordinates detection
                    detectDragGestures(
                        onDragStart = { startPos ->
                            // Convert touch position on viewport relative to canvas translation Matrix
                            val relativeX = (startPos.x - offset.x - size.width / 2f) / scale + proj.width / 2f
                            val relativeY = (startPos.y - offset.y - size.height / 2f) / scale + proj.height / 2f
                            viewModel.drawOrPaintOnLayer(relativeX, relativeY, "MOVE_START")
                        },
                        onDrag = { change, dragAmount ->
                            val currentPos = change.position
                            val relativeX = (currentPos.x - offset.x - size.width / 2f) / scale + proj.width / 2f
                            val relativeY = (currentPos.y - offset.y - size.height / 2f) / scale + proj.height / 2f
                            viewModel.drawOrPaintOnLayer(relativeX, relativeY, "MOVE_DRAG")
                        },
                        onDragEnd = {
                            viewModel.drawOrPaintOnLayer(0f, 0f, "MOVE_END")
                        }
                    )
                }
                .pointerInput(selectedTool) {
                    detectTapGestures { tapPos ->
                        val relativeX = (tapPos.x - offset.x - size.width / 2f) / scale + proj.width / 2f
                        val relativeY = (tapPos.y - offset.y - size.height / 2f) / scale + proj.height / 2f
                        viewModel.drawOrPaintOnLayer(relativeX, relativeY, "TAP")
                    }
                }
        ) {
            // Stacked layers visualization Box
            Box(
                modifier = Modifier
                    .size(proj.width.dp, proj.height.dp)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .background(ComposeColor.LightGray) // background canvas board
                    .drawBehind {
                        // Drawing transparent alpha background tile pattern visually (Checkers board style)
                        val tileSize = 16f
                        var white = true
                        for (y in 0 until (size.height / tileSize).toInt() + 1) {
                            white = y % 2 == 0
                            for (x in 0 until (size.width / tileSize).toInt() + 1) {
                                drawRect(
                                    color = if (white) ComposeColor(0xFFE5E5E5) else ComposeColor(0xFFCCCCCC),
                                    topLeft = Offset(x * tileSize, y * tileSize),
                                    size = androidx.compose.ui.geometry.Size(tileSize, tileSize)
                                )
                                white = !white
                            }
                        }
                    }
                    .testTag("editor_canvas")
            ) {
                // Composited layers rendering
                layers.filter { it.isVisible }.forEach { layer ->
                    val layerBmp = layer.bitmap
                    Image(
                        bitmap = layerBmp.asImageBitmap(),
                        contentDescription = layer.name,
                        contentScale = ContentScale.None,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                alpha = layer.opacity,
                                translationX = layer.offsetX,
                                translationY = layer.offsetY,
                                scaleX = layer.scale,
                                scaleY = layer.scale
                            )
                    )
                }

                // Selection mask boundary outlines overlay
                if (selectionMask != null) {
                    Image(
                        bitmap = selectionMask!!.asImageBitmap(),
                        contentDescription = "Selection Outline Overlay",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Floating Quick Zoom reset visual triggers
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(ComposeColor(0x99252526), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { scale = (scale + 0.2f).coerceAtHeight() }) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = ComposeColor.White)
                }
                IconButton(onClick = { scale = (scale - 0.2f).coerceAtHeight(); if (scale < 0.2f) scale = 0.2f }) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = ComposeColor.White)
                }
                IconButton(onClick = { scale = 1.0f; offset = Offset.Zero }) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Reset Screen Fit", tint = ComposeColor.White)
                }
            }
        }
    }
}

private fun Float.coerceAtHeight(): Float {
    return this.coerceIn(0.2f, 8.0f)
}

// 5. PANNEAU DES CALQUES (Right)
@Composable
fun LayersPanelComponent(
    viewModel: PhotoXViewModel,
    layers: List<LayerState>,
    activeLayerId: Long?
) {
    var activeBlendMode by remember { mutableStateOf("NORMAL") }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(ComposeColor(0xFF252526))
            .padding(12.dp)
    ) {
        Text(
            text = "Calques (${layers.size})",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = ComposeColor.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Layers actions (Add/Remove)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { viewModel.addNewLayer() },
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF3A8EED)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Calque", tint = ComposeColor.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ajouter", fontSize = 11.sp, color = ComposeColor.White)
            }
            Button(
                onClick = {
                    if (activeLayerId != null) {
                        viewModel.deleteLayer(activeLayerId)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFFCF6679)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Remove Calque", tint = ComposeColor.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Supprimer", fontSize = 11.sp, color = ComposeColor.White)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Opacity Controller slider
        if (activeLayerId != null) {
            val activeLayer = layers.find { it.id == activeLayerId }
            if (activeLayer != null) {
                Text(
                    text = "Opacité : ${(activeLayer.opacity * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = ComposeColor.LightGray
                )
                Slider(
                    value = activeLayer.opacity,
                    onValueChange = { viewModel.updateLayerOpacity(activeLayerId, it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = ComposeColor(0xFF3A8EED),
                        activeTrackColor = ComposeColor(0xFF3A8EED)
                    ),
                    modifier = Modifier.height(28.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Blend Modes dropdown chooser
                var showBlendsMenu by remember { mutableStateOf(false) }
                Box {
                    Button(
                        onClick = { showBlendsMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF333333)),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Mode: ${activeLayer.blendMode}",
                            fontSize = 11.sp,
                            color = ComposeColor.White
                        )
                    }

                    val blendModesList = listOf("NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "DARKEN", "LIGHTEN")
                    DropdownMenu(
                        expanded = showBlendsMenu,
                        onDismissRequest = { showBlendsMenu = false },
                        modifier = Modifier.background(ComposeColor(0xFF2A2A2B))
                    ) {
                        blendModesList.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode, color = ComposeColor.White, fontSize = 11.sp) },
                                onClick = {
                                    viewModel.updateLayerBlendMode(activeLayerId, mode)
                                    showBlendsMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Layers rendering list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(layers.reversed()) { idx, layer ->
                val isActive = layer.id == activeLayerId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isActive) ComposeColor(0xFF333333) else ComposeColor(0xFF1E1E1F),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { viewModel.selectLayer(layer.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Small thumbnail bitmap simulated icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(ComposeColor(0xFF2C2C2C), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (layer.type) {
                                "TEXT" -> Icons.Default.TextFields
                                "SHAPE" -> Icons.Default.Category
                                else -> Icons.Default.Image
                            },
                            contentDescription = null,
                            tint = ComposeColor.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = layer.name,
                            color = ComposeColor.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Idx: ${layer.layerIndex}",
                            color = ComposeColor.Gray,
                            fontSize = 10.sp
                        )
                    }
                    // Layer Position movers and Eye visiblity triggers
                    IconButton(
                        onClick = { viewModel.toggleLayerVisibility(layer.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Visiblity",
                            tint = ComposeColor.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Row {
                        IconButton(
                            onClick = { viewModel.moveLayerIndex(layer.id, 1) }, // move up
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.ArrowDropUp, contentDescription = "Monter", tint = ComposeColor.Gray)
                        }
                        IconButton(
                            onClick = { viewModel.moveLayerIndex(layer.id, -1) }, // move down
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Descendre", tint = ComposeColor.Gray)
                        }
                    }
                }
            }
        }
    }
}

// 6. BOTTOM TOOL PROPERTIES PANEL
@Composable
fun PropertiesPanelComponent(
    selectedTool: String,
    brushColorFlow: MutableStateFlow<String>,
    brushSizeFlow: MutableStateFlow<Float>,
    isEraserSizeFlow: MutableStateFlow<Float>,
    magicWandToleranceFlow: MutableStateFlow<Int>,
    textInputFlow: MutableStateFlow<String>,
    shapeTypeFlow: MutableStateFlow<String>
) {
    val context = LocalContext.current
    val brushColor by brushColorFlow.collectAsState()
    val brushSize by brushSizeFlow.collectAsState()
    val eraserSize by isEraserSizeFlow.collectAsState()
    val wandTolerance by magicWandToleranceFlow.collectAsState()
    val textInput by textInputFlow.collectAsState()
    val shapeType by shapeTypeFlow.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(ComposeColor(0xFF252526))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(160.dp)) {
            Text(
                text = "Outil sélectionné :",
                fontSize = 11.sp,
                color = ComposeColor.Gray
            )
            Text(
                text = selectedTool,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ComposeColor(0xFF3999ED)
            )
        }

        VerticalDivider(color = ComposeColor(0xFF3A3A3B), modifier = Modifier.padding(horizontal = 16.dp).fillMaxHeight())

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            when (selectedTool) {
                "Pinceau", "Formes", "Texte" -> {
                    // Size slider
                    Column(modifier = Modifier.width(220.dp)) {
                        Text(
                            text = "Taille pinceau : ${brushSize.toInt()} px",
                            color = ComposeColor.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = brushSize,
                            onValueChange = { brushSizeFlow.value = it },
                            valueRange = 2f..100f,
                            colors = SliderDefaults.colors(thumbColor = ComposeColor(0xFF3A8EED)),
                            modifier = Modifier.height(28.dp)
                        )
                    }

                    // Predefined palettes swatches
                    Column {
                        Text("Couleur de premier plan :", fontSize = 11.sp, color = ComposeColor.Gray)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("#FFFFFF", "#000000", "#FF4B4B", "#3A8EED", "#FFCD3C", "#0BC17F").forEach { hex ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(ComposeColor(Color.parseColor(hex)), CircleShape)
                                        .border(
                                            width = if (brushColor == hex) 2.dp else 0.dp,
                                            color = ComposeColor.White,
                                            shape = CircleShape
                                        )
                                        .clickable { brushColorFlow.value = hex }
                                )
                            }
                        }
                    }

                    if (selectedTool == "Texte") {
                        VerticalDivider(color = ComposeColor(0xFF3A3A3B), modifier = Modifier.fillMaxHeight())
                        // Text String Input field
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Texte à dessiner :", fontSize = 11.sp, color = ComposeColor.Gray)
                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInputFlow.value = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                singleLine = true
                            )
                        }
                    }

                    if (selectedTool == "Formes") {
                        VerticalDivider(color = ComposeColor(0xFF3A3A3B), modifier = Modifier.fillMaxHeight())
                        // Shape selector
                        Column {
                            Text("Type de forme :", fontSize = 11.sp, color = ComposeColor.Gray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Rectangle", "Cercle", "Ligne").forEach { type ->
                                    val isSelected = shapeType == type
                                    Button(
                                        onClick = { shapeTypeFlow.value = type },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) ComposeColor(0xFF3A8EED) else ComposeColor(0xFF333333)
                                        ),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.height(28.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text(type, fontSize = 10.sp, color = ComposeColor.White)
                                    }
                                }
                            }
                        }
                    }
                }

                "Gomme" -> {
                    Column(modifier = Modifier.width(220.dp)) {
                        Text(
                            text = "Taille Gomme : ${eraserSize.toInt()} px",
                            color = ComposeColor.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = eraserSize,
                            onValueChange = { isEraserSizeFlow.value = it },
                            valueRange = 4f..150f,
                            colors = SliderDefaults.colors(thumbColor = ComposeColor(0xFFCF6679)),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                "Baguette magique" -> {
                    Column(modifier = Modifier.width(220.dp)) {
                        Text(
                            text = "Tolérance de couleur : $wandTolerance",
                            color = ComposeColor.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = wandTolerance.toFloat(),
                            onValueChange = { magicWandToleranceFlow.value = it.toInt() },
                            valueRange = 2f..120f,
                            colors = SliderDefaults.colors(thumbColor = ComposeColor(0xFF3A8EED)),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                "Tampon" -> {
                    Column {
                        Text(
                            text = "Taille du tampon : ${brushSize.toInt()} px",
                            color = ComposeColor.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = brushSize,
                            onValueChange = { brushSizeFlow.value = it },
                            valueRange = 4f..100f,
                            colors = SliderDefaults.colors(thumbColor = ComposeColor(0xFF3A8EED)),
                            modifier = Modifier.width(220.dp).height(28.dp)
                        )
                    }
                    Text(
                        text = "Alt+Clic / Premier clic fixe la source de clonage, puis peignez sur un autre point.",
                        color = ComposeColor.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                else -> {
                    // Default generic tip properties
                    Text(
                        text = "Sélectionnez un point ou faites glisser le canevas pour interagir avec l'outil de précision.",
                        color = ComposeColor.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// 7. DEVELOPEMENT CAMERA RAW DIALOGS COMPONENT
@Composable
fun CameraRawWizardComponent(
    originalBmp: Bitmap,
    previewBmp: Bitmap?,
    viewModel: PhotoXViewModel,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val exposure by viewModel.rawExposure.collectAsState()
    val contrast by viewModel.rawContrast.collectAsState()
    val highlights by viewModel.rawHighlights.collectAsState()
    val shadows by viewModel.rawShadows.collectAsState()
    val whites by viewModel.rawWhites.collectAsState()
    val blacks by viewModel.rawBlacks.collectAsState()
    val clarity by viewModel.rawClarity.collectAsState()
    val vibrance by viewModel.rawVibrance.collectAsState()
    val saturation by viewModel.rawSaturation.collectAsState()
    val temperature by viewModel.rawTemperature.collectAsState()
    val tint by viewModel.rawTint.collectAsState()
    val noiseReduction by viewModel.rawNoiseReduction.collectAsState()
    val sharpness by viewModel.rawSharpness.collectAsState()

    var showCompareOriginal by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = false) {}, // consume clicks
        color = ComposeColor(0xFF1E1E1E)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT PANEL: Visual Render Preview and Comparer
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(ComposeColor(0xFF121212))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Aperçu de développement Camera RAW",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                val renderBmp = if (showCompareOriginal) originalBmp else (previewBmp ?: originalBmp)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(ComposeColor.Black, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = renderBmp.asImageBitmap(),
                        contentDescription = "Camera RAW developer render preview",
                        modifier = Modifier.padding(12.dp).fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showCompareOriginal = true
                                        tryAwaitRelease()
                                        showCompareOriginal = false
                                    }
                                )
                            },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF333333))
                    ) {
                        Text("Maintenir pour comparer (Avant/Après)", color = ComposeColor.White)
                    }

                    Text(
                        text = if (showCompareOriginal) "Affichage : ORIGINAL" else "Affichage : AJUSTÉ",
                        color = if (showCompareOriginal) ComposeColor(0xFFCF6679) else ComposeColor(0xFF0BC17F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            VerticalDivider(color = ComposeColor(0xFF2C2C2C), modifier = Modifier.width(1.dp).fillMaxHeight())

            // RIGHT PANEL: Controls sliders list
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .background(ComposeColor(0xFF252526))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Ajustements du capteur RAW",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = ComposeColor.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Development Slider Builder helper
                val drawSlider = @Composable { label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, fontSize = 11.sp, color = ComposeColor.LightGray)
                            Text(String.format("%.2f", value), fontSize = 11.sp, color = ComposeColor(0xFF3A8EED))
                        }
                        Slider(
                            value = value,
                            onValueChange = {
                                onChange(it)
                                viewModel.updateCameraRawPreview()
                            },
                            valueRange = min..max,
                            colors = SliderDefaults.colors(
                                thumbColor = ComposeColor(0xFF3A8EED),
                                activeTrackColor = ComposeColor(0xFF3A8EED)
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                drawSlider("Exposition", exposure, -2.0f, 2.0f) { viewModel.rawExposure.value = it }
                drawSlider("Contraste", contrast, -1.0f, 1.0f) { viewModel.rawContrast.value = it }
                drawSlider("Hautes lumières", highlights, -1.0f, 1.0f) { viewModel.rawHighlights.value = it }
                drawSlider("Ombres", shadows, -1.0f, 1.0f) { viewModel.rawShadows.value = it }
                drawSlider("Blancs", whites, -1.0f, 1.0f) { viewModel.rawWhites.value = it }
                drawSlider("Noirs", blacks, -1.0f, 1.0f) { viewModel.rawBlacks.value = it }
                drawSlider("Clarté", clarity, 0.0f, 1.0f) { viewModel.rawClarity.value = it }
                drawSlider("Vibrance", vibrance, -1.0f, 1.0f) { viewModel.rawVibrance.value = it }
                drawSlider("Saturation", saturation, 0.0f, 2.0f) { viewModel.rawSaturation.value = it }
                drawSlider("Température", temperature, -1.0f, 1.0f) { viewModel.rawTemperature.value = it }
                drawSlider("Teinte", tint, -1.0f, 1.0f) { viewModel.rawTint.value = it }
                drawSlider("Réduction du bruit", noiseReduction, 0.0f, 1.0f) { viewModel.rawNoiseReduction.value = it }
                drawSlider("Netteté", sharpness, 0.0f, 1.0f) { viewModel.rawSharpness.value = it }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF424242)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Annuler", color = ComposeColor.White)
                    }
                    Button(
                        onClick = onApply,
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF3A8EED)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Développer", color = ComposeColor.White)
                    }
                }
            }
        }
    }
}

// Dialog to create a custom dimension canvas
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int) -> Unit
) {
    val templates = listOf("Flyer A6", "Affiche A4", "Carte visite", "Logo carré", "Personnalisé")
    var selectedTemplate by remember { mutableStateOf("Logo carré") }
    var widthText by remember { mutableStateOf("512") }
    var heightText by remember { mutableStateOf("512") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ComposeColor(0xFF252526),
            modifier = Modifier.padding(16.dp).testTag("dialog_new_project")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Nouveau Document",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Template Picker
                Text("Choisir un format de canevas :", fontSize = 12.sp, color = ComposeColor.LightGray)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.forEach { temp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selectedTemplate == temp) ComposeColor(0xFF333333) else ComposeColor.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { selectedTemplate = temp }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTemplate == temp,
                                onClick = { selectedTemplate = temp },
                                colors = RadioButtonDefaults.colors(selectedColor = ComposeColor(0xFF3A8EED))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(temp, color = ComposeColor.White, fontSize = 14.sp)
                        }
                    }
                }

                if (selectedTemplate == "Personnalisé") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it },
                            label = { Text("Largeur (px)") },
                            textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it },
                            label = { Text("Hauteur (px)") },
                            textStyle = androidx.compose.ui.text.TextStyle(color = ComposeColor.White),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler", color = ComposeColor.LightGray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val w = widthText.toIntOrNull() ?: 512
                            val h = heightText.toIntOrNull() ?: 512
                            onCreate(selectedTemplate, w, h)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF3A8EED))
                    ) {
                        Text("Créer", color = ComposeColor.White)
                    }
                }
            }
        }
    }
}

// Dialog to select color and make actions
@Composable
fun ColorSelectDialog(
    title: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        Pair("Blanc pur", "#FFFFFF"),
        Pair("Noir profond", "#000000"),
        Pair("Rouge vif", "#FF4B4B"),
        Pair("Bleu PhotoX", "#3A8EED"),
        Pair("Jaune Soleil", "#FFCD3C"),
        Pair("Vert Printemps", "#0BC17F"),
        Pair("Violet Néon", "#9B51E0")
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ComposeColor(0xFF252526),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onColorSelected(opt.second) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(ComposeColor(Color.parseColor(opt.second)), CircleShape)
                                    .border(1.dp, ComposeColor.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(opt.first, color = ComposeColor.White, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annuler", color = ComposeColor.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
fun VerticalDivider(color: ComposeColor, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(1.dp)
            .background(color)
    )
}
