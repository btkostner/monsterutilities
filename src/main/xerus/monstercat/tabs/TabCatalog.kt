package xerus.monstercat.tabs

import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.text.Font
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import xerus.ktutil.collections.ArraySet
import xerus.ktutil.containsAny
import xerus.ktutil.javafx.MenuItem
import xerus.ktutil.javafx.TableColumn
import xerus.ktutil.javafx.fill
import xerus.ktutil.javafx.onFx
import xerus.ktutil.javafx.properties.listen
import xerus.ktutil.javafx.textWidth
import xerus.ktutil.javafx.ui.controls.MultiSearchable
import xerus.ktutil.javafx.ui.controls.SearchView
import xerus.ktutil.javafx.ui.controls.SearchableColumn
import xerus.ktutil.javafx.ui.controls.Type
import xerus.ktutil.toLocalDate
import xerus.monstercat.Settings
import xerus.monstercat.api.APIUtils
import xerus.monstercat.api.Player
import xerus.monstercat.api.Playlist
import xerus.monstercat.api.response.Track
import java.time.LocalTime
import kotlin.math.absoluteValue

val defaultColumns = arrayOf("Genre", "Artists", "Track", "Length")
val availableColumns = arrayOf("ID", "Date", "B", "CC", "E", "Genre", "Subgenres", "Artists", "Track", "Comp", "Length", "BPM", "Key", "Fan Ratings")
private fun isColumnCentered(colName: String) = colName.containsAny("id", "cc", "date", "bpm", "length", "key", "comp", "rating") || colName == "B" || colName == "E"

class TabCatalog: TableTab() {
	
	private val searchView = SearchView<List<String>>()
	private val searchables = searchView.options
	
	init {
		table.setRowFactory {
			TableRow<List<String>>().apply {
				val genre = cols.find("Genre") ?: return@apply
				itemProperty().listen {
					style = genreColor(it?.get(genre)?.let {
						genreColors.find(it)
							?: genreColors.find(it.split(' ').map { it.first() }.joinToString(separator = ""))
					}) ?: "-fx-background-color: transparent"
				}
			}
		}
		
		searchables.setAll(MultiSearchable("Any", Type.TEXT) { it }, MultiSearchable("Genre", Type.TEXT) { val c = cols.findAll("genre"); it.filterIndexed { index, _ -> c.contains(index) } })
		setColumns(Settings.LASTCATALOGCOLUMNS.all)
		
		children.add(searchView)
		predicate.bind(searchView.predicate)
		
		fill(table)
		table.visibleLeafColumns.addListener(ListChangeListener {
			it.next(); Settings.VISIBLECATALOGCOLUMNS.putMulti(*it.addedSubList.map { it.text }.toTypedArray())
		})
		
		fun playTracks(add: Boolean) {
			val selected = table.selectionModel.selectedItems ?: return
			GlobalScope.launch {
				if(!add)
					Player.playTracks(getSongs(selected))
				else
					Playlist.addAll(getSongs(selected))
			}
		}
		table.setOnMouseClicked { me ->
			when {
				me.clickCount == 2 && me.button == MouseButton.PRIMARY -> playTracks(false)
				me.button == MouseButton.MIDDLE -> playTracks(true)
			}
		}
		table.setOnKeyPressed { ke ->
			when(ke.code) {
				KeyCode.ENTER -> playTracks(false)
				KeyCode.PLUS, KeyCode.ADD -> playTracks(true)
				else -> return@setOnKeyPressed
			}
		}
		
		table.selectionModel.selectionMode = SelectionMode.MULTIPLE
		
		val rightClickMenu = ContextMenu()
		rightClickMenu.items.addAll(
			MenuItem("Play") {
				val selected = table.selectionModel.selectedItems
				GlobalScope.launch {
					Player.playTracks(getSongs(selected))
				}
			},
			MenuItem("Add to playlist") {
				val selected = table.selectionModel.selectedItems
				GlobalScope.launch {
					Playlist.addAll(getSongs(selected))
				}
			},
			MenuItem("Play next") {
				val selected = table.selectionModel.selectedItems
				GlobalScope.launch {
					Playlist.addAll(getSongs(selected), true)
				}
			},
			SeparatorMenuItem(),
			MenuItem("Select All") {
				table.selectionModel.selectAll()
			}
		)
		table.contextMenu = rightClickMenu
	}
	
	private suspend fun getSongs(songList: ObservableList<List<String>>): ArrayList<Track> {
		val tracklist = arrayListOf<Track>()
		songList.forEach { item ->
			APIUtils.find(item[cols.findUnsafe("Track")].trim(), item[cols.findUnsafe("Artist")])
				?.let { tracklist.add(it) }
				?: logger.warn("Failed matching song ${item[cols.findUnsafe("Artist")]} - ${item[cols.findUnsafe("Track")].trim()} while adding it to playlist")
		}
		return tracklist
	}
	
	private fun setColumns(columns: List<String>) {
		val visibleColumns = Settings.VISIBLECATALOGCOLUMNS()
		val newColumns = ArrayList<TableColumn<List<String>, *>>(columns.size)
		for(colName in columns) {
			val existing = table.columns.find { it.text == colName }
			if(existing != null) {
				newColumns.add(existing)
				continue
			}
			try {
				val notFound = ArraySet<String>()
				val colValue = { list: List<String> ->
					cols.find(colName)
						.also {
							if(it == null && notFound.add(colName))
								logger.warn("Column $colName not found!")
						}
						?.let { list.getOrNull(it) }
						.also {
							if(it == null && notFound.add(colName))
								logger.debug("No value for $colName found in $list")
						}
				}
				val col: TableColumn<List<String>, *> = when {
					colName.contains("bpm", true) ->
						SearchableColumn.simple(colName, Type.INT)
						{ colValue(it)?.substringBefore(' ')?.toIntOrNull() }
					colName.contains("date", true) ->
						SearchableColumn.simple(colName, Type.DATE)
						{ colValue(it)?.toLocalDate() }
					colName.containsAny("time", "length") ->
						SearchableColumn.simple(colName, Type.LENGTH)
						converter@{
							colValue(it)?.split(":")?.map {
								it.toIntOrNull() ?: return@converter null
							}?.let { LocalTime.of(it[0] / 60, it[0] % 60, it[1]) }
						}
					colName.contains("genre", true) ->
						TableColumn<List<String>, String>(colName)
						{ colValue(it.value) ?: "" }
					else -> SearchableColumn.simple(colName, Type.TEXT, colValue::invoke)
				}
				if(col is SearchableColumn<List<String>, *, *>)
					searchables.add(col)
				if(isColumnCentered(colName))
					col.style = "-fx-alignment: CENTER"
				newColumns.add(col)
				col.isVisible = visibleColumns.contains(colName, true)
			} catch(e: Exception) {
				logger.warn("TabCatalog column initialization failed with $e", e)
			}
		}
		table.columns.setAll(newColumns)
	}
	
	override fun sheetToData(sheet: List<List<String>>) {
		super.sheetToData(sheet.drop(1))
		Settings.LASTCATALOGCOLUMNS.putMulti(*cols.keys.toTypedArray())
		onFx {
			setColumns(cols.keys)
			table.columns.forEach { col ->
				@Suppress("UNCHECKED_CAST")
				val widths = ArrayList<Double>(table.items.size)
				for(item in table.items) {
					// improve get font from cells
					// (skin.tableHeaderRow.getColumnHeaderFor(col)?.lookup(".label") as? Label)?.font.printNamed("header font")
					widths.add(col.getCellData(item)?.toString()?.textWidth(Font.font("System", 11.0)) ?: 0.0 + 6)
				}
				val avg = widths.average()
				val deviation = widths.sumByDouble { (it - avg).absoluteValue } / widths.size
				col.prefWidth = avg
				col.minWidth = (avg - deviation).coerceAtLeast(Label(col.text).textWidth().plus(5).coerceAtLeast(30.0))
				col.maxWidth = widths.max()!!
				logger.trace { "Catalog column %-11s avg %3.0f +-%2.0f  max %3.0f  min %2.0f".format(col.text, avg, deviation, col.maxWidth, col.minWidth) }
			}
		}
	}
	
}
