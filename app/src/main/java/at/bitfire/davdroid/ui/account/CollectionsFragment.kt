package at.bitfire.davdroid.ui.account

import android.app.Application
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.*
import android.widget.PopupMenu
import androidx.annotation.AnyThread
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.paging.PagedList
import androidx.paging.PagedListAdapter
import androidx.paging.toLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewbinding.ViewBinding
import at.bitfire.davdroid.Constants
import at.bitfire.davdroid.DavService
import at.bitfire.davdroid.R
import at.bitfire.davdroid.databinding.AccountCollectionsBinding
import at.bitfire.davdroid.model.AppDatabase
import at.bitfire.davdroid.model.Collection
import at.bitfire.davdroid.resource.LocalAddressBook
import at.bitfire.davdroid.resource.TaskUtils
import at.bitfire.davdroid.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class CollectionsFragment: Fragment(), SwipeRefreshLayout.OnRefreshListener {

    companion object {
        const val EXTRA_SERVICE_ID = "serviceId"
        const val EXTRA_COLLECTION_TYPE = "collectionType"
    }

    protected var _binding: AccountCollectionsBinding? = null
    protected val binding get() = _binding!!

    val accountModel by activityViewModels<AccountActivity.Model>()
    val model by viewModels<Model> {
        object: ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel?> create(modelClass: Class<T>): T =
                    Model(requireActivity().application, accountModel) as T
        }
    }

    abstract val noCollectionsStringId: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = AccountCollectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // don't get the activity ViewModel in onCreate(), it may crash
        model.initialize(
                arguments?.getLong(EXTRA_SERVICE_ID) ?: throw IllegalArgumentException("EXTRA_SERVICE_ID required"),
                arguments?.getString(EXTRA_COLLECTION_TYPE) ?: throw IllegalArgumentException("EXTRA_COLLECTION_TYPE required")
        )

        model.isRefreshing.observe(viewLifecycleOwner, Observer { nowRefreshing ->
            binding.swipeRefresh.isRefreshing = nowRefreshing
        })
        model.hasWriteableCollections.observe(viewLifecycleOwner, Observer {
            requireActivity().invalidateOptionsMenu()
        })
        model.collections.observe(viewLifecycleOwner, Observer { collections ->
            val colors = collections.orEmpty()
                    .filterNotNull()
                    .mapNotNull { it.color }
                    .distinct()
                    .ifEmpty { listOf(Constants.DAVDROID_GREEN_RGBA) }
            binding.swipeRefresh.setColorSchemeColors(*colors.toIntArray())
        })
        binding.swipeRefresh.setOnRefreshListener(this)

        val updateProgress = Observer<Boolean> {
            if (model.isSyncActive.value == true) {
                binding.progress.isIndeterminate = true
                binding.progress.alpha = 1.0f
                binding.progress.visibility = View.VISIBLE
            } else {
                if (model.isSyncPending.value == true) {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.alpha = 0.2f
                    binding.progress.isIndeterminate = false
                    binding.progress.progress = 100
                } else
                    binding.progress.visibility = View.INVISIBLE
            }
        }
        model.isSyncPending.observe(viewLifecycleOwner, updateProgress)
        model.isSyncActive.observe(viewLifecycleOwner, updateProgress)

        val adapter = createAdapter()
        binding.list.layoutManager = LinearLayoutManager(requireActivity())
        binding.list.adapter = adapter
        model.collections.observe(viewLifecycleOwner, Observer { data ->
            adapter.submitList(data)

            if (data.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.empty.visibility = View.VISIBLE
            } else {
                binding.list.visibility = View.VISIBLE
                binding.empty.visibility = View.GONE
            }
        })

        binding.noCollections.setText(noCollectionsStringId)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val settings = SettingsManager.getInstance(requireActivity())

        menu.findItem(R.id.showOnlyPersonal).let { showOnlyPersonal ->
            accountModel.showOnlyPersonal.value?.let { value ->
                showOnlyPersonal.isChecked = value
            }
            accountModel.showOnlyPersonal_writable.value?.let { writable ->
                showOnlyPersonal.isEnabled = writable
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) =
            when (item.itemId) {
                R.id.refresh -> {
                    onRefresh()
                    true
                }
                R.id.showOnlyPersonal -> {
                    accountModel.toggleShowOnlyPersonal()
                    true
                }
                else ->
                    false
            }

    override fun onRefresh() {
        model.refresh()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    protected abstract fun checkPermissions()
    protected abstract fun createAdapter(): CollectionAdapter


    abstract class CollectionViewHolder<T: ViewBinding>(
            parent: ViewGroup,
            val binding: T,
            protected val accountModel: AccountActivity.Model
    ): RecyclerView.ViewHolder(binding.root) {
        abstract fun bindTo(item: Collection)
    }

    abstract class CollectionAdapter(
            protected val accountModel: AccountActivity.Model
    ): PagedListAdapter<Collection, CollectionViewHolder<*>>(DIFF_CALLBACK) {

        companion object {
            private val DIFF_CALLBACK = object: DiffUtil.ItemCallback<Collection>() {
                override fun areItemsTheSame(oldItem: Collection, newItem: Collection) =
                        oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: Collection, newItem: Collection) =
                        oldItem == newItem
            }
        }

        override fun onBindViewHolder(holder: CollectionViewHolder<*>, position: Int) {
            getItem(position)?.let { item ->
                holder.bindTo(item)
            }
        }

    }

    class CollectionPopupListener(
            private val accountModel: AccountActivity.Model,
            private val item: Collection
    ): View.OnClickListener {

        override fun onClick(anchor: View) {
            val fragmentManager = (anchor.context as AppCompatActivity).supportFragmentManager
            val popup = PopupMenu(anchor.context, anchor, Gravity.RIGHT)
            popup.inflate(R.menu.account_collection_operations)

            with(popup.menu.findItem(R.id.force_read_only)) {
                if (item.type == Collection.TYPE_WEBCAL)
                    // Webcal collections are always read-only
                    isVisible = false
                else {
                    // non-Webcal collection
                    if (item.privWriteContent)
                        isChecked = item.forceReadOnly
                    else
                        isVisible = false
                }
            }
            popup.menu.findItem(R.id.delete_collection).isVisible = item.privUnbind

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.force_read_only -> {
                        accountModel.toggleReadOnly(item)
                    }
                    R.id.properties ->
                        CollectionInfoFragment.newInstance(item.id).show(fragmentManager, null)
                    R.id.delete_collection ->
                        DeleteCollectionFragment.newInstance(accountModel.account, item.id).show(fragmentManager, null)
                }
                true
            }
            popup.show()
        }

    }


    class Model(
            val context: Application,
            val accountModel: AccountActivity.Model
    ): ViewModel(), DavService.RefreshingStatusListener, SyncStatusObserver {

        private val db = AppDatabase.getInstance(context)

        val serviceId = MutableLiveData<Long>()
        private lateinit var collectionType: String

        // cache task provider
        val taskProvider by lazy { TaskUtils.currentProvider(context) }

        val hasWriteableCollections: LiveData<Boolean> = Transformations.switchMap(serviceId) { service ->
            db.homeSetDao().hasBindableByService(service)
        }
        val collections: LiveData<PagedList<Collection>> =
                Transformations.switchMap(accountModel.showOnlyPersonal) { onlyPersonal ->
                    if (onlyPersonal)
                        // only personal collections
                        Transformations.switchMap(serviceId) { _serviceId ->
                            db.collectionDao().pagePersonalByServiceAndType(_serviceId, collectionType).toLiveData(25)
                        }
                    else
                        // all collections
                        Transformations.switchMap(serviceId) { _serviceId ->
                            db.collectionDao().pageByServiceAndType(_serviceId, collectionType).toLiveData(25)
                        }
                }

        // observe DavService refresh status
        @Volatile
        private var davService: DavService.InfoBinder? = null
        private var davServiceConn: ServiceConnection? = null
        private val svcConn = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val svc = service as DavService.InfoBinder
                davService = svc
                svc.addRefreshingStatusListener(this@Model, true)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                davService = null
            }
        }
        val isRefreshing = MutableLiveData<Boolean>()

        // observe whether sync is active
        private var syncStatusHandle: Any? = null
        val isSyncActive = MutableLiveData<Boolean>()
        val isSyncPending = MutableLiveData<Boolean>()


        fun initialize(id: Long, collectionType: String) {
            this.collectionType = collectionType
            if (serviceId.value == null)
                serviceId.value = id

            if (context.bindService(Intent(context, DavService::class.java), svcConn, Context.BIND_AUTO_CREATE))
                davServiceConn = svcConn

            viewModelScope.launch(Dispatchers.Default) {
                syncStatusHandle = ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_PENDING + ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE, this@Model)
                checkSyncStatus()
            }
        }

        override fun onCleared() {
            syncStatusHandle?.let { ContentResolver.removeStatusChangeListener(it) }

            davService?.removeRefreshingStatusListener(this)
            davServiceConn?.let {
                context.unbindService(it)
                davServiceConn = null
            }
        }


        fun refresh() {
            val intent = Intent(context, DavService::class.java)
            intent.action = DavService.ACTION_REFRESH_COLLECTIONS
            intent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, serviceId.value)
            context.startService(intent)
        }

        @AnyThread
        override fun onDavRefreshStatusChanged(id: Long, refreshing: Boolean) {
            if (id == serviceId.value)
                isRefreshing.postValue(refreshing)
        }

        @AnyThread
        override fun onStatusChanged(which: Int) {
            checkSyncStatus()
        }

        @AnyThread
        @Synchronized
        private fun checkSyncStatus() {
            if (collectionType == Collection.TYPE_ADDRESSBOOK) {
                val mainAuthority = context.getString(R.string.address_books_authority)
                val mainSyncActive = ContentResolver.isSyncActive(accountModel.account, mainAuthority)
                val mainSyncPending = ContentResolver.isSyncPending(accountModel.account, mainAuthority)

                val addrBookAccounts = LocalAddressBook.findAll(context, null, accountModel.account).map { it.account }
                val syncActive = addrBookAccounts.any { ContentResolver.isSyncActive(it, ContactsContract.AUTHORITY) }
                val syncPending = addrBookAccounts.any { ContentResolver.isSyncPending(it, ContactsContract.AUTHORITY) }

                isSyncActive.postValue(mainSyncActive || syncActive)
                isSyncPending.postValue(mainSyncPending || syncPending)
            } else {
                val authorities = mutableListOf(CalendarContract.AUTHORITY)
                taskProvider?.let {
                    authorities += it.authority
                }
                isSyncActive.postValue(authorities.any {
                    ContentResolver.isSyncActive(accountModel.account, it)
                })
                isSyncPending.postValue(authorities.any {
                    ContentResolver.isSyncPending(accountModel.account, it)
                })
            }
        }

    }

}